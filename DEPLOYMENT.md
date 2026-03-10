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
  │  docker push dkr-private.nexus-t01.sunrise.ch/myapp:1.0
  ▼
OCP HAProxy Router  (TLS edge termination, balance: source → sticky sessions)
  │  Route: dkr-private.nexus-t01.sunrise.ch → Service: nexus-docker-proxy (:8082)
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
Docker sends  →  POST https://dkr-private.nexus-t01.sunrise.ch/v2/myapp/blobs/uploads/
OCP Route     →  TLS terminates, forwards to HAProxy sidecar (port 8082)
HAProxy       →  Host header: dkr-private.nexus-t01.sunrise.ch
              →  Map lookup: dkr-private.nexus-t01.sunrise.ch → dkr-private
              →  Rewrite: /v2/myapp/blobs/uploads/ → /repository/dkr-private/v2/myapp/blobs/uploads/
              →  Proxy to: http://localhost:8081/repository/dkr-private/v2/myapp/blobs/uploads/
```

### Session Affinity (Solves "Unknown Blob" / UUID Mismatch)

- `balance: source` on the OCP Route ties the client IP to the **same Nexus pod**
- HAProxy sidecar proxies to `localhost:8081` — traffic never leaves the pod
- All blob uploads for a single `docker push` go to the same Nexus instance
- **Requires:** GKE Cloud NAT with a static IP (dynamic IP breaks affinity)

---

## Prerequisites Checklist

| # | Prerequisite | Who | Detail |
|---|---|---|---|
| 1 | **Nexus Pro License** | You | Valid `.lic` file for HA/clustered mode |
| 2 | **PostgreSQL Database** | DBA team | Host, port (5432), DB name, user, password |
| 3 | **Shared Storage** | Storage team | RWX PersistentVolume (Isilon/NFS) accessible by all pods |
| 4 | **Wildcard DNS** | DNS team | `*.nexus-t01.sunrise.ch` → OCP Router LB IP |
| 5 | **Wildcard TLS Cert** | SSL team | Internal cert for `*.nexus-t01.sunrise.ch` (with `nexus-t01.sunrise.ch` as SAN) |
| 6 | **GKE DaemonSet Update** | You | Add the internal CA cert to Docker trust store on GKE nodes |
| 7 | **Static Cloud NAT IP** | GCP team | Ensure `natIpAllocateOption: MANUAL_ONLY` |

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

See [README.md](README.md) for ESO configuration with AWS SM / Azure KV / GCP SM / HashiCorp Vault.

---

## values.yaml — What to Update

### Must Update

| Parameter | Description | Example |
|---|---|---|
| `namespaces.nexusNs.name` | OCP namespace | `nx-poc` |
| `secret.db.user` | DB username | `nxrm_user` |
| `secret.db.password` | DB password | `<secure>` |
| `secret.db.host` | DB host | `cloudsql-proxy.nx-poc.svc` |
| `secret.nexusAdminSecret.adminPassword` | Initial admin password | `admin123` |

### Should Review

| Parameter | Default | Change If |
|---|---|---|
| `statefulset.replicaCount` | `2` | Want 3+ pods |
| `statefulset.container.resources` | 8Gi/8cpu | Sizing differs |
| `storageClass.name` | — | Set to your RWX storage class |
| `pvc.accessModes` | `ReadWriteOnce` | Change to `ReadWriteMany` for HA |
| `pvc.storage` | `2Gi` | Set to production size |
| `dockerProxy.haproxy.image` | `registry.access.redhat.com/rhel9/haproxy:latest` | If Red Hat registry unavailable, use `haproxy:2.9-alpine` |

### Docker Repos — Customize Hostnames

All repos use `*.nexus-t01.sunrise.ch` subdomains (covered by one wildcard cert):

```yaml
dockerProxy:
  route:
    repos:
      - name: dkr-4-test
        host: dkr-4-test.nexus-t01.sunrise.ch       # ← change host to your FQDN
        repo: dkr-4-test                             # ← must match Nexus repo name
```

To change a hostname, just edit the `host` field and run `helm upgrade`.

---

## Installation

### Step 1: Prepare your values file

Copy `values.yaml` to `my-values.yaml` and update the parameters listed above.

### Step 2: Install

```bash
helm install nexus ./nxrm-ha \
  -n nx-poc --create-namespace \
  -f my-values.yaml \
  --set-file secret.license.licenseSecret.file=./your-license.lic \
  --set-file dockerProxy.route.tls.certificate=./wildcard.crt \
  --set-file dockerProxy.route.tls.key=./wildcard.key \
  --set-file nexusRoute.tls.certificate=./wildcard.crt \
  --set-file nexusRoute.tls.key=./wildcard.key
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
# Expected: 9 routes (8 Docker + 1 UI)
```

### Step 4: Test connectivity

```bash
# Nexus UI
curl -v https://nexus-t01.sunrise.ch

# Docker V2 API check
curl -v https://dkr-4-test.nexus-t01.sunrise.ch/v2/

# Docker push
docker login dkr-4-test.nexus-t01.sunrise.ch
docker tag alpine:latest dkr-4-test.nexus-t01.sunrise.ch/test:v1
docker push dkr-4-test.nexus-t01.sunrise.ch/test:v1
```

---

## Post-Installation: Nexus Repository Config

1. Login to Nexus UI at `https://nexus-t01.sunrise.ch`
2. Go to **Administration** → **Repository** → **Repositories**
3. For each Docker hosted repo (`dkr-4-test`, `dkr-private`, etc.):
   - **HTTP connector**: leave **empty** (no port)
   - **HTTPS connector**: leave **empty** (no port)
   - The HAProxy sidecar handles routing — Nexus uses path-based routing on port 8081

---

## Common Operations

### Change a Repo Hostname

Edit `host` in `values.yaml` → `helm upgrade`:
```yaml
- name: dkr-private
  host: new-hostname.nexus-t01.sunrise.ch     # just change this
  repo: dkr-private                           # keep same
```

### Add a New Docker Repository

1. Create the repo in Nexus UI (no connector port)
2. Add to `values.yaml`:
   ```yaml
   - name: dkr-new
     host: dkr-new.nexus-t01.sunrise.ch
     repo: dkr-new
   ```
3. Run `helm upgrade`

### Production Migration

When moving from test to production:
1. Get cert for `*.nexus.sunrise.ch` (production domain)
2. Update all `host` fields and `domain` in `values.yaml`
3. Update Jenkins pipeline registry URLs
4. Update GKE DaemonSet to trust the new CA
5. Run `helm upgrade`

---

## Upgrading

```bash
helm upgrade nexus ./nxrm-ha -n nx-poc -f my-values.yaml \
  --set-file secret.license.licenseSecret.file=./your-license.lic \
  --set-file dockerProxy.route.tls.certificate=./wildcard.crt \
  --set-file dockerProxy.route.tls.key=./wildcard.key \
  --set-file nexusRoute.tls.certificate=./wildcard.crt \
  --set-file nexusRoute.tls.key=./wildcard.key
```

## Uninstalling

```bash
helm uninstall nexus -n nx-poc
```

> **Note:** PVCs are NOT deleted on uninstall. Delete manually if needed: `oc delete pvc -n nx-poc --all`

---

## Troubleshooting

### "Unknown blob" / UUID mismatch
```bash
# Check HAProxy sidecar logs
oc logs <pod> -n nx-poc -c haproxy-sidecar

# Check Nexus logs
oc logs <pod> -n nx-poc -c nxrm-app | grep -i "uuid\|unknown blob"

# Verify session affinity — all requests should hit same pod
for i in {1..5}; do
  curl -s -o /dev/null -w "%{remote_ip}\n" https://dkr-4-test.nexus-t01.sunrise.ch/v2/
done
```

### Docker login returns 503
Nexus can take up to 5 minutes on first boot (DB migration). Check:
```bash
oc describe pod <pod> -n nx-poc | grep -A5 "startup"
```

### HAProxy config errors
```bash
oc exec <pod> -n nx-poc -c haproxy-sidecar -- haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg
```

### Verify host-to-repo map
```bash
oc exec <pod> -n nx-poc -c haproxy-sidecar -- cat /usr/local/etc/haproxy/host-to-repo.map
```

---

## Configuration Reference

| Parameter | Description | Default |
|---|---|---|
| `dockerProxy.enabled` | Enable HAProxy sidecar | `true` |
| `dockerProxy.containerPort` | Sidecar listen port | `8082` |
| `dockerProxy.haproxy.image` | HAProxy image | `registry.access.redhat.com/rhel9/haproxy:latest` |
| `dockerProxy.haproxy.maxconn` | Max connections | `4096` |
| `dockerProxy.haproxy.timeout*` | Various timeouts | `60s` to `3600s` |
| `dockerProxy.route.enabled` | Create OCP Routes | `true` |
| `dockerProxy.route.domain` | Parent domain (for wildcard Route) | `nexus-t01.sunrise.ch` |
| `dockerProxy.route.balance` | LB algorithm for sticky sessions | `source` |
| `dockerProxy.route.wildcard.enabled` | Create additional wildcard Route | `false` |
| `dockerProxy.route.repos[].name` | Route resource identifier | — |
| `dockerProxy.route.repos[].host` | FQDN used by Docker clients | — |
| `dockerProxy.route.repos[].repo` | Nexus repository name | — |
| `dockerProxy.route.tls.enabled` | Enable TLS on Docker Routes | `true` |
| `dockerProxy.route.tls.termination` | TLS termination type | `edge` |
| `dockerProxy.route.tls.certificate` | TLS cert PEM (or `--set-file`) | — |
| `dockerProxy.route.tls.key` | TLS private key PEM | — |
| `nexusRoute.enabled` | Create Nexus UI Route | `true` |
| `nexusRoute.host` | UI hostname | `nexus-t01.sunrise.ch` |
| `nexusRoute.tls.*` | UI Route TLS config | `edge` termination |
| `podDisruptionBudget.enabled` | Create PDB | `true` |
| `podDisruptionBudget.minAvailable` | Min pods during disruption | `1` |
