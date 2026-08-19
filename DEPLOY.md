# Evalia-AI 部署指南（Docker Compose 全量 · 域名 + HTTPS）

本文档面向**全新 Linux 服务器**，从 0 到 1 把 Evalia-AI 跑起来：前端（Caddy，自动 HTTPS）→ 后端 Spring Boot → MySQL / Redis / MinIO / Kafka 全部容器化，一条命令启动。

## 1. 部署架构

```
用户浏览器
   │  HTTPS(443) / HTTP(80)
   ▼
┌─────────────────────────────┐
│  web  = Caddy               │  静态前端资源 + /api 反向代理 + 自动签发证书
└─────────────┬───────────────┘
              │  /api/* (http)
              ▼
┌─────────────────────────────┐
│  backend = Spring Boot :8080 │  唯一业务进程（含 Kafka 消费者 EvalWorker/JudgeWorker）
└────┬──────┬──────┬──────┬───┘
     │      │      │      │
 MySQL  Redis  MinIO  Kafka(ZooKeeper)
```

对外**只暴露 80 / 443**，其余服务都在 Docker 内部网络 `evalnet` 中，公网不可直接访问。

## 2. 服务器选购建议

| 配置 | 说明 |
|---|---|
| 最低 2C4G | 能跑起来，评测任务并发高时会慢 |
| **推荐 4C8G** | 评测类应用建议，任务并发 + LLM 调用的吞吐更稳 |
| 磁盘 | SSD 40GB+（系统 + Docker 镜像 + MySQL/MinIO 数据） |
| 系统 | Ubuntu 22.04 / 24.04 LTS（本指南以其为例） |
| 带宽 | 面向国内用户选国内云厂商；纯演示 3~5Mbps 即可 |

## 3. 前置准备

### 3.1 域名解析
在 DNS 控制台把你的域名添加一条 **A 记录**，指向服务器公网 IP。Caddy 自动申请 HTTPS 证书依赖 80/443 可达 + 域名解析正确。

### 3.2 防火墙 / 安全组
云厂商安全组（或服务器防火墙）放行：

```
80/tcp   HTTP（证书校验）    443/tcp  HTTPS        22/tcp  SSH
```

> ⚠️ 不要放行 3306 / 6379 / 9092 / 9000 / 9001 / 8080 等内部端口。

### 3.3 安装 Docker
```bash
curl -fsSL https://get.docker.com | sh
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
# 重新登录（或 newgrp docker）使 docker 组生效
docker compose version   # 确认 compose 插件可用
```

## 4. 一键部署

### 4.1 拉取代码
```bash
cd ~
git clone https://github.com/zhanbetter/-.git evalias
cd evalias
```

### 4.2 配置环境变量
```bash
cp .env.prod.example .env
# 生成模型 API Key 加密密钥（如果还没有）
openssl rand -base64 32
```
编辑 `.env`，至少修改几项：

```ini
DOMAIN=你的域名            # 如 eval.xxx.com
DB_PASSWORD=强密码1
MINIO_PASSWORD=强密码2
EVAL_ENCRYPT_KEY=上一步生成的密钥
```

> `.env` 已在 `.gitignore` 中，不会误提交。

### 4.3 构建并启动
```bash
docker compose -f docker-compose.prod.yml up -d --build
```

首次启动会自动完成：
1. MySQL 首次空卷时执行 `sql/init.sql`（完整建表 + 示例评估器）
2. `minio-init` 等待 MinIO 就绪后自动创建 `llm-eval` bucket
3. `backend` 等 MySQL/Kafka 健康后启动（含 Kafka 消费者，`auto-startup=true`）
4. `web`(Caddy) 等后端健康后对外服务，并**自动申请 HTTPS 证书**

### 4.4 升级已有部署（非首次启动）

版本升级引入数据库变更时（`sql/migration_*.sql`），需先手动执行增量脚本再重启：

```bash
docker compose -f docker-compose.prod.yml exec mysql mysql -uroot -p${DB_PASSWORD} llm_eval < sql/migration_prompt_versioning.sql
docker compose -f docker-compose.prod.yml exec mysql mysql -uroot -p${DB_PASSWORD} llm_eval < sql/migration_three_state_verdict.sql
docker compose -f docker-compose.prod.yml exec mysql mysql -uroot -p${DB_PASSWORD} llm_eval < sql/migration_eval_article.sql
docker compose -f docker-compose.prod.yml exec mysql mysql -uroot -p${DB_PASSWORD} llm_eval < sql/migration_rss_source.sql
docker compose -f docker-compose.prod.yml exec mysql mysql -uroot -p${DB_PASSWORD} llm_eval < sql/migration_rss_source_v2.sql
docker compose -f docker-compose.prod.yml exec mysql mysql -uroot -p${DB_PASSWORD} llm_eval < sql/seed_rss_sources.sql
docker compose -f docker-compose.prod.yml restart backend
```

> 本地/已有环境快速补齐：若不确定缺哪些迁移，直接执行 **`sql/migration_patch_all.sql`**（幂等，列/索引/表已存在会自动跳过，可重复执行），一个文件追平全部 13 批迁移——维度列、人工校验/多评委/金标准表、数据集 data 能力列、文章表、评估器版本化、三态判定、订阅源 v2 及预置 5 个订阅源：
> ```bash
> cmd /c "mysql --default-character-set=utf8mb4 -uroot -p1234 llm_eval" < sql\migration_patch_all.sql
> ```

最新迁移说明：
- `migration_prompt_versioning.sql`：评估器版本化 + strict_output（老版本 build 的升级者执行）
- `migration_three_state_verdict.sql`：最终判定三态 goodcase/badcase/unknown（`eval_task_summary` 增加 `good_count`/`unknown_count`）
- `migration_eval_article.sql`：评测知识文章表（新增 RSS 文章管理功能）
- `migration_rss_source.sql`：RSS 订阅源表（新增源管理 + 每日 08:30 自动拉取）
- `migration_rss_source_v2.sql`：订阅源加 `source_type` 列（支持腾讯云 JSON 接口源）+ 预置美团/腾讯云两个源（**已执行过旧版 migration_rss_source 的环境必须执行**）
- `migration_patch_all.sql`：**全量幂等补丁**（合并全部 13 批迁移，列/索引/表已存在自动跳过），漏跑多批升级时直接执行它兜底（见上方提示）
- `seed_rss_sources.sql`：预置订阅源（InfoQ / 阮一峰周刊 / HuggingFace / 美团 / 腾讯云），`INSERT IGNORE` 可安全重复执行（依赖 v2 的 source_type 列）

> 提示：定时任务默认每天 08:30 拉取全部启用源，可通过后端配置 `eval.rss.cron` 修改 cron 表达式。

### 4.5 查看状态
```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend
```

## 5. 验证

浏览器打开 `https://你的域名` 应看到登录/首页。之后在平台内：

1. 配置模型（被测模型 + 裁判模型，API Key 会加密存库）
2. 上传数据集 → 创建评测任务 → 确认任务能跑完并出 badcase 率

若任务一直不执行，先查：
```bash
docker compose -f docker-compose.prod.yml logs -f backend | grep -iE "kafka|worker|judge"
```

## 6. 常用运维命令

```bash
# 查看整体状态
docker compose -f docker-compose.prod.yml ps

# 滚动查看某个服务日志
docker compose -f docker-compose.prod.yml logs -f --tail=200 backend

# 重启单个服务
docker compose -f docker-compose.prod.yml restart backend

# 停服务（数据卷保留）
docker compose -f docker-compose.prod.yml down

# 升降级/重新部署（代码更新后）
git pull
docker compose -f docker-compose.prod.yml up -d --build
```

## 7. 数据备份 / 恢复

### 备份数据库
```bash
docker compose -f docker-compose.prod.yml exec -T mysql \
  mysqldump -uroot -p"$DB_PASSWORD" llm_eval > evalias_backup_$(date +%F).sql
```

### 备份文件（MinIO 存储的数据集文件）
```bash
sudo tar czf minio_data_$(date +%F).tar.gz /var/lib/docker/volumes/evalias_minio_data
```

### 恢复
```bash
# MySQL：进入容器导入
docker compose -f docker-compose.prod.yml exec -T mysql \
  mysql -uroot -p"$DB_PASSWORD" llm_eval < evalias_backup_xxx.sql
```

## 8. 安全清单（上线前逐项确认）

- [ ] 域名解析 + 80/443 放行，其余端口未对外开放
- [ ] `.env` 中 `DB_PASSWORD` / `MINIO_PASSWORD` 已改为强密码（非默认值）
- [ ] `EVAL_ENCRYPT_KEY` 已配置独立密钥（未配置会回退到代码内置的开发密钥）
- [ ] MinIO Console（9001）默认不对外；需要管理时临时加端口映射或走 SSH 隧道
- [ ] SSH 建议：密钥登录禁密码、安装 fail2ban、禁止 root 远程登录

## 9. 常见问题排查

| 现象 | 排查 |
|---|---|
| HTTPS 证书一直不签发 | 域名 A 记录是否指向本机 IP？80/443 安全组是否放行？Caddy 日志：`logs -f web` |
| 后端反复重启 | MySQL 健康检查未过 → 检查 `.env` 密码是否与 `init.sql`/MySQL 一致；看 `logs backend` |
| 评测任务不执行 | Kafka 未健康：`docker compose -f docker-compose.prod.yml ps kafka`；后端 Kafka listener 是否启动（日志应有 `收到评测任务`） |
| 前端页面打开但 API 报错 | 浏览器 F12 → 网络面板看 `/api` 请求是否 502；502 说明后端未就绪或崩溃 |
| 改了 `.env` 不生效 | `.env` 需在**项目根目录**；改后 `up -d` 重建 backend/web |
| MinIO 里没 bucket | 手动补建：`docker compose -f docker-compose.prod.yml run --rm minio-init` |

## 10. 低内存服务器（2G）专项优化

如果服务器只有 2G 内存（如阿里云轻量2C2G），`docker-compose.prod.yml` 已做如下调优：

| 组件 | 默认堆内存 | 优化后 | 说明 |
|---|---|---|---|
| Backend JVM | -Xmx768m | -Xmx384m | 通过 JAVA_OPTS 环境变量覆盖 |
| Kafka | -Xmx1G | -Xmx256m | KAFKA_HEAP_OPTS 环境变量 |
| Zookeeper | -Xmx512m | -Xmx128m | KAFKA_HEAP_OPTS 环境变量 |
| MySQL | buffer_pool 128M | buffer_pool 256M | command 启动参数 |
| Redis | 无限制 | 64MB 上限 | allkeys-lru 淘汰策略 |

**强烈建议创建 swap 分区**（1~2G），防止突发内存超限导致 OOM Kill：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
# 持久化（重启后自动挂载）
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
# 降低 swappiness（尽量用物理内存）
echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf
sudo sysctl -p
```

> ⚠️ 2G + 2G swap ≈ 4G 可用内存，基本够用。但评测任务并发不宜超过2，JVM 偶尔会因 GC 压力触发 swap，响应变慢属正常现象。如需更稳定体验，建议升级到 4G 内存。

## 11. 性能参考

- 后端 JVM 默认 `-Xms256m -Xmx768m`（在 `llm-eval-server/Dockerfile` 中设置），生产 compose 通过 `JAVA_OPTS` 环境变量覆盖为 `-Xms128m -Xmx384m`，可在 `.env` 中进一步调整
- Kafka 为容器默认堆内存；2C4G 下建议控制评测任务并发，避免 OOM
- 评测任务本身是异步队列，压力大时可后续扩展为多实例水平扩展（增加 backend 副本）