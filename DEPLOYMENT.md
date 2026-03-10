# Nexus HA on OpenShift — Deployment Guide

This chart deploys Sonatype Nexus Repository Pro in HA mode on OpenShift with an **HAProxy sidecar** for Docker registry support using subdomain-based routing.

## Architecture

```
Docker client
  │  docker push dkr-4-test.nexus-t01.sunrise.ch/myapp:1.0
  ▼
OCP HAProxy Router  (balance: source → sticky sessions)
  │  Wildcard Route: *.nexus-t01.sunrise.ch
  ▼
┌───────────────── Nexus Pod ─────────────────┐
│  HAProxy Sidecar (:8082)   Nexus (:8081)    │
│  - extracts repo name      - path-based     │
│    from Host header          routing on 8081 │
│  - rewrites /v2/... →     - shared DB       │
│    /repository/<repo>/v2/  - shared blob    │
│  - proxies to localhost      store           │
└─────────────────────────────────────────────┘
```

---

## Prerequisites

### 1. Nexus Repository Pro License

A valid Pro license is required for HA/clustered mode.

### 2. PostgreSQL Database

An external PostgreSQL database accessible from the OCP cluster. Collect:

| Parameter | Example |
|---|---|
| DB Host | `cloudsql-proxy.nx-poc.svc` or `10.x.x.x` |
| DB Port | `5432` |
| DB Name | `nexus` |
| DB User | `nxrm_user` |
| DB Password | `<secure-password>` |

### 3. Shared Storage (Blob Store)

A `ReadWriteMany` (RWX) PersistentVolume for the shared blob store, accessible by all Nexus pods.

| Storage Type | Example |
|---|---|
| Isilon/NFS | NFS server IP + export path |
| CephFS | Ceph cluster + CephFS subvolume |

### 4. DNS

| Record | Value |
|---|---|
| `*.nexus-t01.sunrise.ch` | CNAME → OCP Router LB address |
| `nexus-t01.sunrise.ch` | CNAME → OCP Router LB address |

### 5. TLS Certificates

| Certificate | Covers |
|---|---|
| Wildcard cert | `*.nexus-t01.sunrise.ch` (for Docker repos) |
| Standard cert | `nexus-t01.sunrise.ch` (for Nexus UI) |

> **Note:** A single wildcard cert covers both if it includes the bare domain as a SAN.

### 6. OCP Wildcard Route Policy

Check if your cluster allows wildcard Routes:

```bash
oc get ingresscontroller default -n openshift-ingress-operator \
  -o jsonpath='{.spec.routeAdmission.wildcardPolicy}'
```

- If output is `WildcardsAllowed` → you're good.
- If output is empty or `None` → request cluster-admin to run:
  ```bash
  oc patch ingresscontroller default -n openshift-ingress-operator \
    --type=merge -p '{"spec":{"routeAdmission":{"wildcardPolicy":"WildcardsAllowed"}}}'
  ```
- **If wildcard is not possible**, set `dockerProxy.route.wildcard.enabled=false` in `values.yaml`. The chart will create one Route per repo instead.

### 7. GKE Cloud NAT (if Jenkins runs in GKE)

The Cloud NAT must use a **static IP** — dynamic IP allocation can rotate mid-push, breaking session affinity.

```bash
# Check current NAT config
gcloud compute routers nats describe <NAT_NAME> --router=<ROUTER_NAME> --region=<REGION>
# Look for: natIpAllocateOption: MANUAL_ONLY
```

---

## Secrets to Create Before Installation

The chart requires **three secrets** to be created before `helm install`:

### Option A: Let the Chart Create Secrets (Simplest)

Set these in `values.yaml` and the chart creates the secrets for you:

```yaml
secret:
  dbSecret:
    enabled: true                    # creates database-secret
  db:
    user: nxrm_user
    password: your_db_password
    host: your_db_host
  nexusAdminSecret:
    enabled: true                    # creates admin-password secret
    adminPassword: admin123          # change on first login
  license:
    name: nexus-repo-license.lic
    licenseSecret:
      enabled: true                  # creates license secret
      # provide license via --set-file:
      # --set-file secret.license.licenseSecret.file=./your-license.lic
```

### Option B: Use External Secrets Operator (Recommended for Production)

```yaml
externalsecrets:
  enabled: true
  secretstore:
    name: nexus-secret-store
    spec:
      provider:
        # Configure for your provider (AWS SM, Azure KV, GCP SM, HashiCorp Vault)
  secrets:
    database:
      providerSecretName: nexus-db-credentials    # secret name in your provider
      dbUserKey: username
      dbPasswordKey: password
      dbHostKey: host
    admin:
      providerSecretName: nexus-admin-password
      adminPasswordKey: nexusAdminPassword
    license:
      providerSecretName: nexus-repo-license.lic
```

### Option C: Create Secrets Manually

```bash
# 1. Database secret
oc create secret generic nxrm-ha-dbsecret -n nx-poc \
  --from-literal=db-user=nxrm_user \
  --from-literal=db-password='<password>' \
  --from-literal=db-host='<db-host>'

# 2. Admin password secret
oc create secret generic nxrm-ha-adminsecret -n nx-poc \
  --from-literal=nexus-admin-password='admin123'

# 3. License secret
oc create secret generic nexus-repo-license.lic -n nx-poc \
  --from-file=nexus-repo-license.lic=./your-license.lic
```

Then set `secret.dbSecret.enabled=false`, `secret.nexusAdminSecret.enabled=false`, `secret.license.licenseSecret.enabled=false` and use `statefulset.container.additionalEnv` to reference the secrets.

---

## Installation

### Step 1: Create your custom values file

Create `my-values.yaml` with your environment-specific settings:

```yaml
namespaces:
  nexusNs:
    name: "nx-poc"                     # your namespace

statefulset:
  replicaCount: 2

# --- Database ---
secret:
  dbSecret:
    enabled: true
  db:
    user: nxrm_user
    password: your_db_password
    host: cloudsql-proxy.nx-poc.svc     # or direct IP
  nexusAdminSecret:
    enabled: true
    adminPassword: admin123
  license:
    name: nexus-repo-license.lic
    licenseSecret:
      enabled: true

# --- Storage ---
storageClass:
  enabled: false
  name: your-nfs-storage-class          # e.g. isilon-sc
pvc:
  accessModes: ReadWriteMany             # RWX required for HA
  storage: 50Gi
  volumeClaimTemplate:
    enabled: true

# --- Docker Proxy (HAProxy Sidecar) ---
dockerProxy:
  enabled: true
  containerPort: 8082
  route:
    enabled: true
    domain: nexus-t01.sunrise.ch         # your parent domain
    wildcard:
      enabled: true                      # false if wildcard Routes not allowed
    repos:                               # used when wildcard.enabled=false
      - name: dkr-4-test
      - name: dkr-4-prod
      - name: dkr-4-stage
      - name: dkr-4-dev
      - name: dkr-private
      - name: dkr-public
      - name: dkr-release
      - name: dkr-snapshot
    tls:
      enabled: true
      termination: edge
      # certificate:                     # paste or use --set-file
      # key:

# --- Nexus UI Route ---
nexusRoute:
  enabled: true
  host: nexus-t01.sunrise.ch
  tls:
    enabled: true
    termination: edge

# --- PDB ---
podDisruptionBudget:
  enabled: true
  minAvailable: 1
```

### Step 2: Install

```bash
helm install nexus ./nxrm-ha \
  -n nx-poc --create-namespace \
  -f my-values.yaml \
  --set-file secret.license.licenseSecret.file=./your-license.lic
```

Or with inline TLS certs:

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
# Check pods (should show 2 pods, each with sidecar)
oc get pods -n nx-poc
# Expected: nexus-nxrm-ha-0   3/3  Running
#           nexus-nxrm-ha-1   3/3  Running

# Check routes
oc get routes -n nx-poc

# Test Nexus UI
curl -v https://nexus-t01.sunrise.ch

# Test Docker
curl -v https://dkr-4-test.nexus-t01.sunrise.ch/v2/

# Docker push
docker login dkr-4-test.nexus-t01.sunrise.ch
docker tag alpine:latest dkr-4-test.nexus-t01.sunrise.ch/test:v1
docker push dkr-4-test.nexus-t01.sunrise.ch/test:v1
```

---

## Post-Installation: Nexus Repository Config

After Nexus is running, configure Docker repos in the Nexus UI:

1. Go to **Administration** → **Repository** → **Repositories**
2. For each Docker hosted repo (`dkr-4-test`, `dkr-4-prod`, etc.):
   - **HTTP connector**: leave **empty** (no port)
   - **HTTPS connector**: leave **empty** (no port)
3. The HAProxy sidecar handles routing — Nexus serves all repos on port 8081 via path-based routing internally

---

## Adding New Docker Repos

**Zero Helm changes required** (when using wildcard Route):

1. Create the Docker hosted repo in Nexus UI (no connector port)
2. Use `<repo-name>.nexus-t01.sunrise.ch` in Docker/Jenkins

If using individual Routes (`wildcard.enabled=false`), add the repo to `dockerProxy.route.repos` in `values.yaml` and run `helm upgrade`.

---

## Upgrading

```bash
helm upgrade nexus ./nxrm-ha -n nx-poc -f my-values.yaml
```

## Uninstalling

```bash
helm uninstall nexus -n nx-poc
```

> **Note:** PVCs are NOT deleted on uninstall. Delete manually if needed:
> ```bash
> oc delete pvc -n nx-poc --all
> ```

---

## Troubleshooting

### "Unknown blob" / UUID mismatch errors

1. Check HAProxy sidecar logs:
   ```bash
   oc logs <pod-name> -n nx-poc -c haproxy-sidecar
   ```
2. Check Nexus logs for UUID errors:
   ```bash
   oc logs <pod-name> -n nx-poc -c nxrm-app | grep -i "uuid\|unknown blob"
   ```
3. Verify session affinity is working:
   ```bash
   # Run multiple times — should always hit the same pod
   for i in {1..5}; do
     curl -s -o /dev/null -w "%{remote_ip}\n" https://dkr-4-test.nexus-t01.sunrise.ch/v2/
   done
   ```
4. Verify GKE Cloud NAT has a static IP (dynamic IP breaks `balance: source`)

### Sidecar not starting

1. Check ConfigMap exists:
   ```bash
   oc get cm -n nx-poc | grep haproxy
   ```
2. Check HAProxy config syntax:
   ```bash
   oc exec <pod> -n nx-poc -c haproxy-sidecar -- haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg
   ```

### Docker login fails with 503

- Nexus may still be starting. Check startup probe:
  ```bash
  oc describe pod <pod-name> -n nx-poc | grep -A5 "startup"
  ```
- Nexus can take up to 5 minutes on first boot (DB migration).

---

## Configuration Reference (New Parameters)

| Parameter | Description | Default |
|---|---|---|
| `dockerProxy.enabled` | Enable HAProxy sidecar for Docker URL rewriting | `true` |
| `dockerProxy.containerPort` | HAProxy sidecar listen port | `8082` |
| `dockerProxy.haproxy.image` | HAProxy container image | `registry.access.redhat.com/rhel9/haproxy:latest` |
| `dockerProxy.haproxy.maxconn` | Max connections | `4096` |
| `dockerProxy.haproxy.timeoutConnect` | Connection timeout | `60s` |
| `dockerProxy.haproxy.timeoutClient` | Client timeout | `600s` |
| `dockerProxy.haproxy.timeoutServer` | Server timeout | `600s` |
| `dockerProxy.haproxy.timeoutTunnel` | Tunnel timeout | `3600s` |
| `dockerProxy.route.enabled` | Create OCP Routes for Docker repos | `true` |
| `dockerProxy.route.domain` | Parent domain for subdomains | `nexus-t01.sunrise.ch` |
| `dockerProxy.route.balance` | HAProxy load balancing algorithm | `source` |
| `dockerProxy.route.timeout` | Route timeout | `600s` |
| `dockerProxy.route.timeoutTunnel` | Route tunnel timeout | `3600s` |
| `dockerProxy.route.wildcard.enabled` | Use single wildcard Route | `true` |
| `dockerProxy.route.repos` | List of repo names (used when wildcard disabled) | 8 repos |
| `dockerProxy.route.tls.enabled` | Enable TLS on Docker routes | `true` |
| `dockerProxy.route.tls.termination` | TLS termination type | `edge` |
| `dockerProxy.route.tls.certificate` | TLS certificate (PEM) | `nil` |
| `dockerProxy.route.tls.key` | TLS private key (PEM) | `nil` |
| `nexusRoute.enabled` | Create OCP Route for Nexus UI | `true` |
| `nexusRoute.host` | Hostname for Nexus UI | `nexus-t01.sunrise.ch` |
| `nexusRoute.tls.enabled` | Enable TLS on UI route | `true` |
| `podDisruptionBudget.enabled` | Create PDB | `true` |
| `podDisruptionBudget.minAvailable` | Min pods available during disruption | `1` |
