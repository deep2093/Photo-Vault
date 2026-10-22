#!/usr/bin/env bash
# A throwaway Samba share on the CI machine, so drive code is tested against a real SMB server.
# Same approach as Veyra. Test-only credentials; nothing here is used outside CI.
set -euo pipefail
sudo apt-get update -qq
sudo apt-get install -y -qq samba
sudo systemctl stop smbd nmbd || true
sudo useradd --no-create-home --shell /usr/sbin/nologin pvfixture
printf 'ci-fixture-only\nci-fixture-only\n' | sudo smbpasswd -s -a pvfixture
mkdir -p /tmp/photovault-share
chmod -R a+rwX /tmp/photovault-share
cat > /tmp/photovault-smb.conf <<CONF
[global]
server role = standalone server
map to guest = Bad User
server min protocol = SMB2
smb ports = 445
log file = /tmp/photovault-smb.log
pid directory = /tmp
lock directory = /tmp/photovault-smb-lock
[family]
path = /tmp/photovault-share
guest ok = no
valid users = pvfixture
read only = no
force user = $(id -un)
CONF
sudo smbd --configfile=/tmp/photovault-smb.conf -D
sleep 2
echo "PV_SMB_FIXTURE=1" >> "$GITHUB_ENV"
