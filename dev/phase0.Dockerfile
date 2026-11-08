FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl pkg-config zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

COPY graalvm-community-jdk-25i4-25.0.4.1.1_linux-aarch64_bin.tar.gz /tmp/graal.tgz
RUN mkdir -p /opt \
    && tar -xzf /tmp/graal.tgz -C /opt \
    && rm /tmp/graal.tgz \
    && ln -s /opt/graalvm-* /opt/graalvm
ENV JAVA_HOME=/opt/graalvm
ENV PATH="/opt/graalvm/bin:/opt/maven/bin:/root/.cargo/bin:${PATH}"

ARG MAVEN_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/4.0.0-rc-7/apache-maven-4.0.0-rc-7-bin.tar.gz
RUN curl -fsSL "$MAVEN_URL" | tar -xz -C /opt \
    && ln -s /opt/apache-maven-4.0.0-rc-7 /opt/maven

RUN curl --proto '=https' --tlsv1.2 -fsSL https://sh.rustup.rs | sh -s -- -y --default-toolchain stable

WORKDIR /src
