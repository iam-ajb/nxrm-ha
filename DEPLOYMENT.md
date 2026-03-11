# Nexus HA on OpenShift — Deployment Guide

> **⚠️ This chart has been modified from Sonatype's official [`nxrm-ha`](https://github.com/sonatype/nxrm3-ha-repository) Helm chart (v90.1.0).**
> The following customizations have been added for OpenShift Docker registry support:
> - **HAProxy sidecar** for Docker V2 URL rewriting (no dedicated connector ports needed)
> - **OCP Route templates** for Docker repos (subdomain-based) and Nexus UI
> - **Host-to-repo mapping** via HAProxy map file (replicates httpd vhost pattern)
> - **PodDisruptionBudget** for HA protection during rolling updates

---

## Architecture

```
Docker client (Jenkins on GKE)
  │  docker push dkr-private.apps.ocp-t-infra-01.swi.srse.net/myapp:1.0
  ▼
OCP HAProxy Router  (TLS edge termination, balance: source → sticky sessions)
  │  Route: dkr-private.apps.ocp-t-infra-01.swi.srse.net → Service: nexus-docker-proxy (:8082)
  ▼
┌───────────────── Nexus Pod ──────────────────────────────────┐
│                                                              │
│  HAProxy Sidecar (:8082)              Nexus App (:8081)      │
│  ┌─────────────────────┐              ┌──────────────────┐   │
│  │ 1. Read Host header │              │                  │   │
│  │ 2. Lookup map file  │──proxy──────▶│ Path-based       │   │
│  │ 3. Rewrite URL:     │  localhost   │ routing on 8081  │   │
│  │    /v2/... →        │   :8081      │                  │   │
│  │    /repository/     │              │ Shared DB        │   │
│  │    <repo>/v2/...    │              │ Shared Blob Store│   │
│  └─────────────────────┘              └──────────────────┘   │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### URL Rewriting Flow

```
Docker sends  →  POST https://dkr-private.apps.ocp-t-infra-01.swi.srse.net/v2/myapp/blobs/uploads/
OCP Route     →  TLS terminates, forwards to HAProxy sidecar (port 8082)
HAProxy       →  Host header: dkr-private.apps.ocp-t-infra-01.swi.srse.net
              →  Map lookup: dkr-private.apps.ocp-t-infra-01.swi.srse.net → dkr-private
              →  Rewrite: /v2/myapp/blobs/uploads/ → /repository/dkr-private/v2/myapp/blobs/uploads/
              →  Proxy to: http://localhost:8081/repository/dkr-private/v2/myapp/blobs/uploads/
```

### Session Affinity (Solves "Unknown Blob" / UUID Mismatch)

- **`balance: source`** on the OCP Route ties the client IP to the **same Nexus pod**.
- **HAProxy sidecar proxies to `localhost:8081`** — traffic never eaves the pod, preventing UUID mismatch entirely.
- All blob uploads for a single `docker push` go to the same Nexus instance.
- **Requires:** GKE Cloud NAT with a static IP (dynamic IP breaks affinity).

---

## Route and TLS Strategy (Zero Extra Certs for Docker)

This chart uses the OpenShift cluster's default wildcard domain (`*.apps.ocp-t-infra-01.swi.srse.net`) for all Docker repos to completely avoid managing custom certificates inside Docker clients or the Nexus Helm chart. 

| Route | Host | Cert Needed? |
|---|---|---|
| **Docker repos** | `dkr-4-test.apps.ocp-t-infra-01.swi.srse.net`, etc. | ❌ No (Cluster default cert covers it) |
| **Primary UI** | `nexus-t01.apps.ocp-t-infra-01.swi.srse.net` | ❌ No (Cluster default cert covers it) |
| **Additional UI** | `nexus-t01.sunrise.ch` | ✅ Yes (`nexus-t01` TLS cert required) |

**Benefits:**
- **Zero TLS warnings** from Jenkins/Docker clients (cluster cert is already trusted).
- **No `--insecure-registry`** needed anymore.
- **No DNS records to create** for Docker repos (`*.apps.ocp-t-infra-01.swi.srse.net` automatically resolves).

---

## Prerequisites Checklist

| # | Prerequisite | Who | Detail |
|---|---|---|---|
| 1 | **Nexus Pro License** | You | Valid `.lic` file for HA/clustered mode |
| 2 | **PostgreSQL Database** | DBA team | Host, port (5432), DB name, user, password |
| 3 | **Shared Storage** | Storage team | RWX PersistentVolume (Isilon/NFS) accessible by all pods |
| 4 | **UI DNS** | DNS team | `nexus-t01.sunrise.ch` → OCP Router LB IP (only if you want the custom UI URL) |
| 5 | **UI TLS Cert** | SSL team | Cert for `nexus-t01.sunrise.ch` |
| 6 | **Static Cloud NAT IP** | GCP team | Ensure GKE NAT has `natIpAllocateOption: MANUAL_ONLY` |

### Verify GKE Cloud NAT
```bash
gcloud compute routers nats describe <NAT_NAME> --router=<ROUTER_NAME> --region=<REGION>
# Expect: natIpAllocateOption: MANUAL_ONLY
```

---

## Secrets

The chart requires **three secrets**. Choose one approach:

### Option A: Chart-Managed (Simplest)

Set in `values.yaml`:
```yaml
secret:
  dbSecret:
    enabled: true
  db:
    user: nxrm_user
    password: <your_db_password>
    host: <your_db_host>
  nexusAdminSecret:
    enabled: true
    adminPassword: admin123              # change on first login
  license:
    name: nexus-repo-license.lic
    licenseSecret:
      enabled: true
      # provide at install: --set-file secret.license.licenseSecret.file=./license.lic
```

### Option B: Create Manually Before Install

```bash
# Database credentials
oc create secret generic nxrm-ha-dbsecret -n nx-poc \
  --from-literal=db-user=nxrm_user \
  --from-literal=db-password='<password>' \
  --from-literal=db-host='<db-host>'

# Admin password
oc create secret generic nxrm-ha-adminsecret -n nx-poc \
  --from-literal=nexus-admin-password='admin123'

# License
oc create secret generic nexus-repo-license.lic -n nx-poc \
  --from-file=nexus-repo-license.lic=./your-license.lic
```

Then set `secret.dbSecret.enabled=false`, `secret.nexusAdminSecret.enabled=false`, `secret.license.licenseSecret.enabled=false` in `values.yaml`.

### Option C: External Secrets Operator

See [README.md](README.md) for ESO configuration.

---

## values.yaml — What to Update

### Must Update

| Parameter | Description |
|---|---|
| `namespaces.nexusNs.name` | OCP namespace (`nx-poc`) |
| `secret.db.user` | DB username (`nxrm_user`) |
| `secret.db.password` | DB password |
| `secret.db.host` | DB host |
| `secret.nexusAdminSecret.adminPassword` | Initial admin password |

### Should Review

| Parameter | Default | Change If |
|---|---|---|
| `statefulset.replicaCount` | `2` | Want 3+ pods |
| `storageClass.name` | — | Set to your RWX storage class |
| `pvc.accessModes` | `ReadWriteOnce` | Change to `ReadWriteMany` for HA |

### Docker Repos — Customize Hostnames

All repos use `*.apps.ocp-t-infra-01.swi.srse.net` subdomains:

```yaml
dockerProxy:
  route:
    domain: apps.ocp-t-infra-01.swi.srse.net                # OCP default wildcard domain
    repos:
      - name: dkr-4-test
        host: dkr-4-test.apps.ocp-t-infra-01.swi.srse.net   # ← automatically covered by OCP cert
        repo: dkr-4-test
```

To change a repo or add a new one, edit this list. The sidecar map file + Routes generate automatically.

---

## Installation

### Step 1: Prepare your values file

Copy `values.yaml` to `my-values.yaml` and update the parameters listed above.

### Step 2: Install

If using the secondary UI Route (`nexus-t01.sunrise.ch`), provide the cert via `--set-file`. **No cert files are needed for the Docker routes !**

```bash
helm install nexus ./nxrm-ha \
  -n nx-poc --create-namespace \
  -f my-values.yaml \
  --set-file secret.license.licenseSecret.file=./your-license.lic \
  --set-file nexusRoute.additionalHosts[0].tls.certificate=./nexus-t01.crt \
  --set-file nexusRoute.additionalHosts[0].tls.key=./nexus-t01.key
```

### Step 3: Verify deployment

```bash
# Pods should show sidecar containers
oc get pods -n nx-poc
# Expected:
#   nexus-nxrm-ha-0   3/3  Running
#   nexus-nxrm-ha-1   3/3  Running

# Routes
oc get routes -n nx-poc
# Expected: 10 routes (8 Docker + 2 UI)
```

### Step 4: Test connectivity

```bash
# Nexus Primary UI
curl -v https://nexus-t01.apps.ocp-t-infra-01.swi.srse.net

# Nexus Secondary UI
curl -v https://nexus-t01.sunrise.ch

# Docker V2 API check
curl -v https://dkr-4-test.apps.ocp-t-infra-01.swi.srse.net/v2/

# Docker push
docker login dkr-4-test.apps.ocp-t-infra-01.swi.srse.net
docker tag alpine:latest dkr-4-test.apps.ocp-t-infra-01.swi.srse.net/test:v1
docker push dkr-4-test.apps.ocp-t-infra-01.swi.srse.net/test:v1
```

---

## Post-Installation: Nexus Repository Config

1. Login to Nexus UI at `https://nexus-t01.apps.ocp-t-infra-01.swi.srse.net` or `https://nexus-t01.sunrise.ch`
2. Go to **Administration** → **Repository** → **Repositories**
3. For each Docker hosted repo (`dkr-4-test`, `dkr-private`, etc.):
   - **HTTP connector**: leave **empty** (no port)
   - **HTTPS connector**: leave **empty** (no port)
   - The HAProxy sidecar handles routing — Nexus uses path-based routing internally

---

## Common Operations

### Add a New Docker Repository

1. Create the repo in Nexus UI (no connector port required)
2. Add to `values.yaml`:
   ```yaml
   - name: dkr-new
     host: dkr-new.apps.ocp-t-infra-01.swi.srse.net
     repo: dkr-new
   ```
3. Run `helm upgrade`

### Debugging Docker Push UUID Error

```bash
# 1. Enable Docker debug on client
DOCKER_CLI_DEBUG=1 docker push dkr-4-test.apps.ocp-t-infra-01.swi.srse.net/myapp:v1

# 2. Check HAProxy sidecar logs (live)
oc logs -f nexus-nxrm-ha-0 -n nx-poc -c haproxy-sidecar

# 3. Check Nexus logs for UUID errors (live)
oc logs -f nexus-nxrm-ha-0 -n nx-poc -c nxrm-app | grep -i "uuid\|unknown blob"
```

---

## Configuration Reference

| Parameter | Description | Default |
|---|---|---|
| `dockerProxy.enabled` | Enable HAProxy sidecar | `true` |
| `dockerProxy.containerPort` | Sidecar listen port | `8082` |
| `dockerProxy.haproxy.image` | HAProxy image | `registry.access.redhat.com/rhel9/haproxy:latest` |
| `dockerProxy.route.domain` | Parent domain | `apps.ocp-t-infra-01.swi.srse.net` |
| `dockerProxy.route.balance` | LB algorithm for sticky sessions | `source` |
| `dockerProxy.route.repos[].name` | Route resource identifier | — |
| `dockerProxy.route.repos[].host` | FQDN used by Docker clients | — |
| `dockerProxy.route.repos[].repo` | Nexus repository name | — |
| `nexusRoute.enabled` | Create Nexus primary UI Route | `true` |
| `nexusRoute.host` | Primary UI hostname | `nexus-t01.apps.ocp-t-infra-01.swi.srse.net` |
| `nexusRoute.additionalHosts[].host` | Additional UI hostnames | `nexus-t01.sunrise.ch` |
| `podDisruptionBudget.enabled` | Create PDB | `true` |
