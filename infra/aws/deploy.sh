#!/usr/bin/env bash
# =============================================================================
#  infra/aws/deploy.sh — Launch an EC2 instance running Qdrant + Neo4j
# -----------------------------------------------------------------------------
#  Prerequisites:
#    brew install awscli
#    aws configure   (or AWS_PROFILE is set)
#
#  Usage:
#    ./infra/aws/deploy.sh            # deploy
#    ./infra/aws/deploy.sh --destroy  # tear everything down
#
#  Required env vars:
#    AWS_REGION     e.g. us-east-1
#
#  Optional env vars:
#    NEO4J_PASS     default: auto-generated
#    QDRANT_API_KEY default: auto-generated
#    ALLOWED_CIDR   default: your current public IP/32
# =============================================================================
set -euo pipefail

AWS_REGION="${AWS_REGION:-us-east-1}"
AWS_PROFILE="${AWS_PROFILE:-saml}"
export AWS_PROFILE

INSTANCE_NAME="guardian-infra"
KEY_NAME="guardian-infra-key"
KEY_FILE="$HOME/.ssh/${KEY_NAME}.pem"
SG_NAME="guardian-infra-sg"

BOLD=$'\033[1m'; GREEN=$'\033[32m'; RED=$'\033[31m'; YELLOW=$'\033[33m'
BLUE=$'\033[34m'; RESET=$'\033[0m'
ok()   { printf '%s✓%s %s\n' "$GREEN"  "$RESET" "$*"; }
fail() { printf '%s✗%s %s\n' "$RED"    "$RESET" "$*"; exit 1; }
warn() { printf '%s!%s %s\n' "$YELLOW" "$RESET" "$*"; }
step() { printf '\n%s%s%s\n' "$BOLD$BLUE" "── $* ──────────────────────────" "$RESET"; }

random_pass() { openssl rand -hex 12; }

# ---- destroy ----------------------------------------------------------------
if [[ "${1:-}" == "--destroy" ]]; then
  step "Destroying guardian-infra EC2 instance"
  INSTANCE_ID=$(aws ec2 describe-instances --region "$AWS_REGION" \
    --filters "Name=tag:Name,Values=$INSTANCE_NAME" "Name=instance-state-name,Values=running,stopped" \
    --query "Reservations[0].Instances[0].InstanceId" --output text 2>/dev/null || echo "")
  if [[ -n "$INSTANCE_ID" && "$INSTANCE_ID" != "None" ]]; then
    aws ec2 terminate-instances --region "$AWS_REGION" --instance-ids "$INSTANCE_ID" >/dev/null
    ok "Terminating $INSTANCE_ID (takes ~1 min to fully stop)"
  else
    warn "No running instance found"
  fi

  SG_ID=$(aws ec2 describe-security-groups --region "$AWS_REGION" \
    --filters "Name=group-name,Values=$SG_NAME" \
    --query "SecurityGroups[0].GroupId" --output text 2>/dev/null || echo "")
  if [[ -n "$SG_ID" && "$SG_ID" != "None" ]]; then
    sleep 15   # wait for instance to release the SG
    aws ec2 delete-security-group --region "$AWS_REGION" --group-id "$SG_ID" 2>/dev/null && ok "Deleted security group" || warn "Could not delete SG yet — retry after instance terminates"
  fi

  [[ -f "$KEY_FILE" ]] && rm -f "$KEY_FILE" && ok "Deleted $KEY_FILE"
  aws ec2 delete-key-pair --region "$AWS_REGION" --key-name "$KEY_NAME" 2>/dev/null && ok "Deleted key pair" || true
  printf '\n%s%s%s\n' "$BOLD$GREEN" "✓ DONE" "$RESET"
  exit 0
fi

# ---- preflight --------------------------------------------------------------
step "Preflight"
command -v aws >/dev/null || fail "awscli not found — brew install awscli"

if [[ -z "${ALLOWED_CIDR:-}" ]]; then
  MY_IP=$(curl -fsS https://checkip.amazonaws.com)
  ALLOWED_CIDR="${MY_IP}/32"
  warn "ALLOWED_CIDR not set — using your current IP: $ALLOWED_CIDR"
fi

aws sts get-caller-identity --region "$AWS_REGION" >/dev/null
ok "AWS credentials valid (region: $AWS_REGION)"

# ---- credentials ------------------------------------------------------------
step "Credentials"
NEO4J_PASS="${NEO4J_PASS:-$(random_pass)}"
QDRANT_API_KEY="${QDRANT_API_KEY:-$(random_pass)}"
ok "Neo4j password: $NEO4J_PASS"
ok "Qdrant API key: $QDRANT_API_KEY"

# ---- SSH key pair -----------------------------------------------------------
step "SSH key pair"
if [[ ! -f "$KEY_FILE" ]]; then
  aws ec2 delete-key-pair --region "$AWS_REGION" --key-name "$KEY_NAME" 2>/dev/null || true
  aws ec2 create-key-pair --region "$AWS_REGION" \
    --key-name "$KEY_NAME" \
    --query "KeyMaterial" --output text > "$KEY_FILE"
  chmod 400 "$KEY_FILE"
  ok "Created key pair → $KEY_FILE"
else
  ok "Reusing existing key → $KEY_FILE"
fi

# ---- security group ---------------------------------------------------------
step "Security group"
SG_ID=$(aws ec2 describe-security-groups --region "$AWS_REGION" \
  --filters "Name=group-name,Values=$SG_NAME" \
  --query "SecurityGroups[0].GroupId" --output text 2>/dev/null || echo "")

if [[ -z "$SG_ID" || "$SG_ID" == "None" ]]; then
  SG_ID=$(aws ec2 create-security-group --region "$AWS_REGION" \
    --group-name "$SG_NAME" \
    --description "Guardian Qdrant + Neo4j" \
    --vpc-id "$VPC_ID" \
    --query "GroupId" --output text)
  # SSH from your IP only
  aws ec2 authorize-security-group-ingress --region "$AWS_REGION" \
    --group-id "$SG_ID" --protocol tcp --port 22 --cidr "$ALLOWED_CIDR" >/dev/null
  # Data ports for the team
  for port in 6333 6334 7474 7687; do
    aws ec2 authorize-security-group-ingress --region "$AWS_REGION" \
      --group-id "$SG_ID" --protocol tcp --port "$port" --cidr "$ALLOWED_CIDR" >/dev/null
  done
  ok "Created security group $SG_ID — ports 22, 6333, 6334, 7474, 7687 → $ALLOWED_CIDR"
else
  ok "Reusing security group: $SG_ID"
fi

# ---- VPC / subnet -----------------------------------------------------------
step "Networking"
VPC_ID=$(aws ec2 describe-vpcs --region "$AWS_REGION" \
  --filters "Name=isDefault,Values=true" \
  --query "Vpcs[0].VpcId" --output text 2>/dev/null || echo "")
if [[ -z "$VPC_ID" || "$VPC_ID" == "None" ]]; then
  VPC_ID=$(aws ec2 describe-vpcs --region "$AWS_REGION" \
    --query "Vpcs[0].VpcId" --output text)
  warn "No default VPC — using first available: $VPC_ID"
else
  ok "VPC: $VPC_ID"
fi

SUBNET_ID=$(aws ec2 describe-subnets --region "$AWS_REGION" \
  --filters "Name=vpc-id,Values=$VPC_ID" \
  --query "Subnets[0].SubnetId" --output text)
ok "Subnet: $SUBNET_ID"

# ---- find latest Amazon Linux 2023 AMI -------------------------------------
step "AMI"
AMI_ID=$(aws ec2 describe-images --region "$AWS_REGION" \
  --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023*-x86_64" \
            "Name=state,Values=available" \
  --query "sort_by(Images, &CreationDate)[-1].ImageId" --output text)
ok "Using AMI: $AMI_ID (Amazon Linux 2023)"

# ---- user-data: install Docker, write compose file, start services ----------
USER_DATA=$(cat <<USERDATA
#!/bin/bash
set -e
dnf install -y docker
systemctl enable --now docker
curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose

mkdir -p /opt/guardian
cat > /opt/guardian/docker-compose.yml <<'COMPOSE'
name: guardian-shared
services:
  qdrant:
    image: qdrant/qdrant:v1.11.0
    environment:
      QDRANT__SERVICE__API_KEY: "${QDRANT_API_KEY}"
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - qdrant-data:/qdrant/storage
    restart: unless-stopped

  neo4j:
    image: neo4j:5.24
    environment:
      NEO4J_AUTH: "neo4j/${NEO4J_PASS}"
      NEO4J_server_memory_pagecache_size: 1G
      NEO4J_server_memory_heap_initial__size: 1G
      NEO4J_server_memory_heap_max__size: 2G
    ports:
      - "7474:7474"
      - "7687:7687"
    volumes:
      - neo4j-data:/data
    restart: unless-stopped

volumes:
  qdrant-data:
  neo4j-data:
COMPOSE

cd /opt/guardian
docker-compose up -d
USERDATA
)

# ---- launch EC2 instance ----------------------------------------------------
step "EC2 instance"

# Check if one already exists
EXISTING=$(aws ec2 describe-instances --region "$AWS_REGION" \
  --filters "Name=tag:Name,Values=$INSTANCE_NAME" "Name=instance-state-name,Values=running,stopped" \
  --query "Reservations[0].Instances[0].InstanceId" --output text 2>/dev/null || echo "")

if [[ -n "$EXISTING" && "$EXISTING" != "None" ]]; then
  warn "Instance $EXISTING already exists — skipping launch"
  INSTANCE_ID="$EXISTING"
else
  INSTANCE_ID=$(aws ec2 run-instances --region "$AWS_REGION" \
    --image-id "$AMI_ID" \
    --instance-type t3.medium \
    --key-name "$KEY_NAME" \
    --security-group-ids "$SG_ID" \
    --subnet-id "$SUBNET_ID" \
    --associate-public-ip-address \
    --user-data "$USER_DATA" \
    --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3"}}]' \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$INSTANCE_NAME}]" \
    --query "Instances[0].InstanceId" --output text)
  ok "Launched instance: $INSTANCE_ID (t3.medium)"
fi

# ---- wait for public IP -----------------------------------------------------
step "Waiting for instance to start"
aws ec2 wait instance-running --region "$AWS_REGION" --instance-ids "$INSTANCE_ID"
PUBLIC_IP=$(aws ec2 describe-instances --region "$AWS_REGION" \
  --instance-ids "$INSTANCE_ID" \
  --query "Reservations[0].Instances[0].PublicIpAddress" --output text)
ok "Instance running — public IP: $PUBLIC_IP"

# ---- wait for services to be reachable (~2 min for Docker + pull) -----------
step "Waiting for Qdrant to be ready (~2 min)"
for i in $(seq 1 24); do
  if curl -fsS -m 3 "http://${PUBLIC_IP}:6333/readyz" >/dev/null 2>&1; then
    ok "Qdrant ready (${i}x5s)"
    break
  fi
  printf '.'
  sleep 5
done
echo ""

# ---- summary ----------------------------------------------------------------
printf '\n%s%s%s\n\n' "$BOLD$GREEN" "✓ DEPLOYMENT COMPLETE" "$RESET"
echo "  Qdrant  REST  → http://${PUBLIC_IP}:6333/dashboard"
echo "  Qdrant  gRPC  → ${PUBLIC_IP}:6334"
echo "  Neo4j   UI    → http://${PUBLIC_IP}:7474  (neo4j / ${NEO4J_PASS})"
echo "  Neo4j   Bolt  → bolt://${PUBLIC_IP}:7687"
echo ""
echo "  SSH access:"
echo "    ssh -i $KEY_FILE ec2-user@${PUBLIC_IP}"
echo ""
echo "  Paste into your .env (and share with teammates):"
echo "    QDRANT_URL=http://${PUBLIC_IP}:6333"
echo "    QDRANT_HOST=${PUBLIC_IP}"
echo "    QDRANT_API_KEY=${QDRANT_API_KEY}"
echo "    NEO4J_URI=bolt://${PUBLIC_IP}:7687"
echo "    NEO4J_PASS=${NEO4J_PASS}"
