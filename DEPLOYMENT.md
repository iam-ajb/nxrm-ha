# Nexus HA on OpenShift — Deployment Guide

This chart deploys Sonatype Nexus Repository Pro in HA mode on OpenShift with an **HAProxy sidecar** for Docker registry support. It replicates the existing httpd reverse proxy pattern where custom DNS hostnames map to specific Nexus Docker repositories — but with TLS on every route.

## Architecture

```
Docker client (Jenkins)
  │  docker push docker-private-t01.sunrise.ch/myapp:1.0
  ▼
OCP HAProxy Router  (balance: source → sticky sessions, TLS edge)
  │  Per-repo Route: docker-private-t01.sunrise.ch
  ▼
┌───────────────── Nexus Pod ─────────────────┐
│  HAProxy Sidecar (:8082)   Nexus (:8081)    │
│  - looks up hostname in    - path-based     │
│    host-to-repo.map          routing on 8081│
│  - rewrites /v2/... →     - shared DB       │
│    /repository/<repo>/v2/  - shared blob    │
│  - proxies to localhost      store          │
└─────────────────────────────────────────────┘
```

### How URL Rewriting Works

| Step | What Happens |
|---|---|
| Docker sends | `POST https://docker-private-t01.sunrise.ch/v2/myapp/blobs/uploads/` |
| OCP Route | TLS terminates, forwards to HAProxy sidecar (port 8082) |
| HAProxy reads Host header | `docker-private-t01.sunrise.ch` |
| HAProxy looks up map file | `docker-private-t01.sunrise.ch` → `dkr-private` |
| HAProxy rewrites path | `/v2/myapp/blobs/uploads/` → `/repository/dkr-private/v2/myapp/blobs/uploads/` |
| HAProxy proxies to Nexus | `localhost:8081/repository/dkr-private/v2/myapp/blobs/uploads/` |

---

## Prerequisites

### 1. Nexus Repository Pro License
A valid Pro license is required for HA/clustered mode.

### 2. PostgreSQL Database
An external PostgreSQL database. Collect: host, port (5432), db name, user, password.

### 3. Shared Storage (Blob Store)
A `ReadWriteMany` (RWX) PersistentVolume (Isilon/NFS/CephFS) accessible by all Nexus pods.

### 4. DNS Records
Create DNS records for each Docker repo hostname and the Nexus UI:

| Record | Points To |
|---|---|
| `nexus-t01.sunrise.ch` | OCP Router LB |
| `dkr-4-test.nexus-t01.sunrise.ch` | OCP Router LB |
| `docker-private-t01.sunrise.ch` | OCP Router LB |
| `docker-public-t01.sunrise.ch` | OCP Router LB |
| _(etc. for each repo)_ | OCP Router LB |

> **Tip:** If all repos use subdomains of the same parent, a wildcard DNS record (`*.nexus-t01.sunrise.ch`) covers all of them.

### 5. TLS Certificates
Each OCP Route needs a TLS cert that covers its hostname. Options:
- **Wildcard cert** (`*.nexus-t01.sunrise.ch`) — covers all subdomain-based repos
- **SAN cert** — single cert with multiple FQDNs
- **Individual certs** — one per repo (most overhead)
- **OCP default cert** — if your cluster has a default wildcard cert on the router

### 6. GKE Cloud NAT (if Jenkins runs in GKE)
Must use a **static IP**. Dynamic IP can rotate mid-push, breaking `balance: source` affinity.

```bash
gcloud compute routers nats describe <NAT_NAME> --router=<ROUTER_NAME> --region=<REGION>
# Expect: natIpAllocateOption: MANUAL_ONLY
```

---

## Secrets (Create Before Installation)

The chart needs three secrets. Choose ONE method:

### Option A: Chart-Managed Secrets (Simplest)

Set in `values.yaml`:
```yaml
secret:
  dbSecret:
    enabled: true
  db:
    user: nxrm_user
    password: your_db_password
    host: your_db_host
  nexusAdminSecret:
    enabled: true
    adminPassword: admin123
  license:
    name: nexus-repo-license.lic
    licenseSecret:
      enabled: true
      # Use --set-file: --set-file secret.license.licenseSecret.file=./license.lic
```

### Option B: Manual Secrets

```bash
oc create secret generic nxrm-ha-dbsecret -n nx-poc \
  --from-literal=db-user=nxrm_user \
  --from-literal=db-password='<password>' \
  --from-literal=db-host='<db-host>'

oc create secret generic nxrm-ha-adminsecret -n nx-poc \
  --from-literal=nexus-admin-password='admin123'

oc create secret generic nexus-repo-license.lic -n nx-poc \
  --from-file=nexus-repo-license.lic=./your-license.lic
```

### Option C: External Secrets Operator (Production)

See the main [README.md](README.md) for ESO configuration with AWS SM / Azure KV / GCP SM.

---

## Installation

### Step 1: Create your custom values file

Create `my-values.yaml`:

```yaml
namespaces:
  nexusNs:
    name: "nx-poc"

statefulset:
  replicaCount: 2

secret:
  dbSecret:
    enabled: true
  db:
    user: nxrm_user
    password: your_db_password
    host: cloudsql-proxy.nx-poc.svc
  nexusAdminSecret:
    enabled: true
    adminPassword: admin123
  license:
    name: nexus-repo-license.lic
    licenseSecret:
      enabled: true

storageClass:
  enabled: false
  name: your-nfs-storage-class
pvc:
  accessModes: ReadWriteMany
  storage: 50Gi
  volumeClaimTemplate:
    enabled: true

# ── Docker Proxy ──
dockerProxy:
  enabled: true
  route:
    enabled: true
    domain: nexus-t01.sunrise.ch
    wildcard:
      enabled: false
    repos:
      - name: dkr-4-test
        host: dkr-4-test.nexus-t01.sunrise.ch
        repo: dkr-4-test
      - name: dkr-private
        host: docker-private-t01.sunrise.ch
        repo: dkr-private
      - name: dkr-public
        host: docker-public-t01.sunrise.ch
        repo: dkr-public
      # ... add more repos as needed

# ── Nexus UI ──
nexusRoute:
  enabled: true
  host: nexus-t01.sunrise.ch
```

### Step 2: Install

```bash
helm install nexus ./nxrm-ha \
  -n nx-poc --create-namespace \
  -f my-values.yaml \
  --set-file secret.license.licenseSecret.file=./your-license.lic
```

With TLS certs:
```bash
helm install nexus ./nxrm-ha \
  -n nx-poc --create-namespace \
  -f my-values.yaml \
  --set-file secret.license.licenseSecret.file=./your-license.lic \
  --set-file dockerProxy.route.tls.certificate=./wildcard.crt \
  --set-file dockerProxy.route.tls.key=./wildcard.key \
  --set-file nexusRoute.tls.certificate=./nexus-ui.crt \
  --set-file nexusRoute.tls.key=./nexus-ui.key
```

### Step 3: Verify

```bash
# Pods should show sidecar
oc get pods -n nx-poc
# Expected: nexus-nxrm-ha-0   3/3  Running

# Routes
oc get routes -n nx-poc

# Test
curl -v https://nexus-t01.sunrise.ch                           # UI
curl -v https://dkr-4-test.nexus-t01.sunrise.ch/v2/            # Docker API
docker login dkr-4-test.nexus-t01.sunrise.ch
docker push dkr-4-test.nexus-t01.sunrise.ch/test:v1
```

---

## Post-Installation: Nexus Config

In Nexus UI → **Administration** → **Repositories**:
- For each Docker hosted repo: leave **HTTP connector empty**, **HTTPS connector empty**
- The HAProxy sidecar handles all routing — Nexus uses path-based routing on port 8081 internally

---

## Common Operations

### Changing a Hostname

Update the `host` field in `values.yaml` and run `helm upgrade`:

```yaml
repos:
  - name: dkr-private
    host: docker-private-new.sunrise.ch    # ← just change this
    repo: dkr-private
```

```bash
helm upgrade nexus ./nxrm-ha -n nx-poc -f my-values.yaml
```

This updates both the OCP Route and the HAProxy map file automatically.

### Adding a New Docker Repository

1. Create the repo in Nexus UI (no connector port)
2. Add it to `dockerProxy.route.repos` in `values.yaml`:
   ```yaml
   - name: dkr-new-repo
     host: docker-new-t01.sunrise.ch
     repo: dkr-new-repo
   ```
3. Create DNS record for the new hostname
4. Run `helm upgrade`

### Mapping to Production DNS (Future)

When migrating from test to production, update the hostnames:

```yaml
repos:
  - name: dkr-private
    host: docker-private.sunrise.ch      # production DNS
    repo: dkr-private
```

Jenkins pipelines won't need `--insecure-registry` anymore since every OCP Route gets TLS automatically.

---

## Troubleshooting

### "Unknown blob" / UUID mismatch
1. Check HAProxy sidecar logs: `oc logs <pod> -n nx-poc -c haproxy-sidecar`
2. Check Nexus logs: `oc logs <pod> -n nx-poc -c nxrm-app | grep -i "uuid\|unknown blob"`
3. Verify Cloud NAT uses a static IP

### Docker login returns 503
Nexus may still be starting (up to 5 mins on first boot for DB migration). Check startup probe:
```bash
oc describe pod <pod> -n nx-poc | grep -A5 "startup"
```

### HAProxy config errors
```bash
oc exec <pod> -n nx-poc -c haproxy-sidecar -- haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg
```

---

## Configuration Reference (Custom Parameters)

| Parameter | Description | Default |
|---|---|---|
| `dockerProxy.enabled` | Enable HAProxy sidecar | `true` |
| `dockerProxy.containerPort` | Sidecar listen port | `8082` |
| `dockerProxy.haproxy.image` | HAProxy image | `registry.access.redhat.com/rhel9/haproxy:latest` |
| `dockerProxy.haproxy.maxconn` | Max connections | `4096` |
| `dockerProxy.haproxy.timeout*` | Various timeouts | `60s` to `3600s` |
| `dockerProxy.route.enabled` | Create OCP Routes | `true` |
| `dockerProxy.route.domain` | Parent domain (for wildcard) | `nexus-t01.sunrise.ch` |
| `dockerProxy.route.balance` | LB algorithm | `source` |
| `dockerProxy.route.wildcard.enabled` | Wildcard Route | `false` |
| `dockerProxy.route.repos[].name` | Route resource name | - |
| `dockerProxy.route.repos[].host` | FQDN for Docker clients | - |
| `dockerProxy.route.repos[].repo` | Nexus repository name | - |
| `dockerProxy.route.tls.*` | TLS config | edge termination |
| `nexusRoute.enabled` | Nexus UI Route | `true` |
| `nexusRoute.host` | UI hostname | `nexus-t01.sunrise.ch` |
| `podDisruptionBudget.enabled` | PDB | `true` |
| `podDisruptionBudget.minAvailable` | Min pods | `1` |
