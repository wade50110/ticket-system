# Jenkins 控制器 image(v0.7 CI/CD)。
# - 官方 LTS(Jenkins 本體跑 JDK 21;官方 jdk17 映像線止於 2.541.3、已停更)
# - 另放 Temurin 17 專供 Maven 建置/測試(與 app 的 Java 17 一致;/etc/mavenrc 讓 mvn 永遠用它,不動 Jenkins 的 JAVA_HOME)
# - 內建 Maven、Node 20(對齊 frontend/Dockerfile 的 node:20-alpine)、docker CLI + buildx(用主機 daemon)、kubectl(與叢集同 minor)
# - plugins 由 plugins.txt 鎖版安裝;JCasC / Job DSL 設定烤進 image → 改設定要重建容器(docker compose --profile ci up -d --build)
# 版本變更請同步 docs/deployment/ci-cd-jenkins.md。
ARG JENKINS_TAG=2.580.1-lts-jdk21

FROM eclipse-temurin:17-jdk AS jdk17

FROM jenkins/jenkins:${JENKINS_TAG}

ARG MAVEN_VERSION=3.9.16
ARG NODE_VERSION=20.20.2
ARG KUBECTL_VERSION=v1.34.1
ARG TARGETARCH=amd64

USER root
ENV TZ=Asia/Taipei

# Temurin 17:只給 Maven 用(不設全域 JAVA_HOME,Jenkins 本體維持 base image 的 JDK 21)
COPY --from=jdk17 /opt/java/openjdk /opt/java/temurin-17

# docker CLI + buildx plugin(Docker 官方 apt repo;daemon 來自 compose 掛進來的 /var/run/docker.sock)
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl xz-utils; \
    install -m 0755 -d /etc/apt/keyrings; \
    curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc; \
    chmod a+r /etc/apt/keyrings/docker.asc; \
    . /etc/os-release; \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian ${VERSION_CODENAME} stable" \
      > /etc/apt/sources.list.d/docker.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends docker-ce-cli docker-buildx-plugin; \
    rm -rf /var/lib/apt/lists/*

# Maven(固定版)+ /etc/mavenrc:mvn 一律用 Temurin 17
RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" -o /tmp/maven.tgz; \
    mkdir -p /opt/maven; \
    tar -xzf /tmp/maven.tgz -C /opt/maven --strip-components=1; \
    rm /tmp/maven.tgz; \
    echo 'JAVA_HOME=/opt/java/temurin-17' > /etc/mavenrc

# Node(固定版,官方 tarball)
RUN set -eux; \
    case "${TARGETARCH}" in amd64) nodearch=x64 ;; arm64) nodearch=arm64 ;; *) echo "unsupported arch ${TARGETARCH}"; exit 1 ;; esac; \
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${nodearch}.tar.xz" -o /tmp/node.txz; \
    mkdir -p /opt/node; \
    tar -xJf /tmp/node.txz -C /opt/node --strip-components=1; \
    rm /tmp/node.txz

# kubectl(與叢集同 minor,sha256 驗證)
RUN set -eux; \
    curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl" -o /usr/local/bin/kubectl; \
    curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl.sha256" -o /tmp/kubectl.sha256; \
    echo "$(cat /tmp/kubectl.sha256)  /usr/local/bin/kubectl" | sha256sum -c -; \
    rm /tmp/kubectl.sha256; \
    chmod +x /usr/local/bin/kubectl

ENV PATH=/opt/maven/bin:/opt/node/bin:${PATH} \
    CASC_JENKINS_CONFIG=/usr/local/jenkins-casc/casc.yaml \
    JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Duser.timezone=Asia/Taipei -Xmx1g"

# JCasC + Job DSL 設定、鎖版 plugins
COPY --chown=jenkins:jenkins casc.yaml jobs.groovy /usr/local/jenkins-casc/
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt

USER jenkins
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
