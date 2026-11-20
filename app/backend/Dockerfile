# The one image of the venue (P16-5, D4): the exchange, the one-shot migration job and the bots,
# chosen by the entrypoint's first argument. Build context: app/backend.
#
#   docker build -t credo-exchange:<git short sha> app/backend
#
# Tests are not run here: the commit being deployed is already green on the development machine
# (D13). No secret is ever copied into the image; every setting is an environment variable.

# --- build -----------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies on their own layer, so a source-only change does not download them again.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package \
    && test -f target/app.jar \
    && test -d target/lib

# --- runtime ---------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

# Non-root user (uid and gid 10001). /data is created here and owned by that user, so a fresh
# named volume mounted on it inherits the ownership; a bind mount on the host must be chowned to
# 10001 (P16-10).
RUN groupadd --system --gid 10001 credo \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin credo \
    && mkdir -p /app/lib /data \
    && chown 10001:10001 /data

WORKDIR /app
COPY --from=build /build/target/lib/ /app/lib/
COPY --from=build /build/target/app.jar /app/app.jar
COPY --chmod=755 docker/entrypoint.sh /app/entrypoint.sh

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["exchange"]
