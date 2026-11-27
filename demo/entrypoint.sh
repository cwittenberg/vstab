#!/bin/sh
set -eu
: "${VSTAB_DEMO_PASSWORD:?Generated demo password is required}"
printf 'developer:%s\n' "$VSTAB_DEMO_PASSWORD" | chpasswd
unset VSTAB_DEMO_PASSWORD
ssh-keygen -A >/dev/null 2>&1
mkdir -p /home/developer/.ssh /home/developer/.cache/vstab
chmod 700 /home/developer/.ssh
if [ -r /opt/vstab-fixture/authorized_keys ]; then
  cp /opt/vstab-fixture/authorized_keys /home/developer/.ssh/authorized_keys
  chmod 600 /home/developer/.ssh/authorized_keys
fi
if [ -r /opt/vstab-cache/code-server-4.146.0-linux-amd64.tar.gz ]; then
  cp /opt/vstab-cache/code-server-4.146.0-linux-amd64.tar.gz /home/developer/.cache/vstab/
fi
chown -R developer:developer /home/developer/.ssh /home/developer/.cache /home/developer/project
if [ ! -d /home/developer/project/.git ]; then
  runuser -u developer -- git -C /home/developer/project init -q
  runuser -u developer -- git -C /home/developer/project config user.name 'vstab demo'
  runuser -u developer -- git -C /home/developer/project config user.email 'demo@vstab.invalid'
  runuser -u developer -- git -C /home/developer/project add .
  runuser -u developer -- git -C /home/developer/project commit -qm 'Initial remote project'
fi
exec /usr/sbin/sshd -D -e -f /etc/ssh/sshd_config.vstab
