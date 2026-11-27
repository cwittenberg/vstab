FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server ca-certificates curl git python3 procps util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --shell /bin/bash developer \
    && mkdir -p /run/sshd
COPY sshd_config /etc/ssh/sshd_config.vstab
COPY entrypoint.sh /usr/local/bin/vstab-demo-entrypoint
COPY project/ /home/developer/project/
RUN chmod 755 /usr/local/bin/vstab-demo-entrypoint && chown -R developer:developer /home/developer/project
EXPOSE 22
ENTRYPOINT ["/usr/local/bin/vstab-demo-entrypoint"]
