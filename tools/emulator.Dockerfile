FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    libx11-6 libxcb1 libxau6 libxdmcp6 libbsd0 libmd0 libnss3 libpulse0 \
    libasound2t64 libdbus-1-3 libxcomposite1 libxcursor1 libxdamage1 libxext6 \
    libxfixes3 libxi6 libxrandr2 libxrender1 libxtst6 libfontconfig1 libfreetype6 \
    libglu1-mesa libxkbcommon0 libvulkan1 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
