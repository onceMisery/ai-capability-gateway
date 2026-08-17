# Stage 1: Build
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build
COPY pom.xml .
COPY gateway-domain/pom.xml gateway-domain/
COPY gateway-application/pom.xml gateway-application/
COPY gateway-adapter-web/pom.xml gateway-adapter-web/
COPY gateway-adapter-postgresql/pom.xml gateway-adapter-postgresql/
COPY gateway-adapter-redis/pom.xml gateway-adapter-redis/
COPY gateway-adapter-llm-http/pom.xml gateway-adapter-llm-http/
COPY gateway-adapter-dubbo/pom.xml gateway-adapter-dubbo/
COPY gateway-adapter-auth-satoken/pom.xml gateway-adapter-auth-satoken/
COPY gateway-adapter-rest/pom.xml gateway-adapter-rest/
COPY gateway-adapter-grpc/pom.xml gateway-adapter-grpc/
COPY gateway-bootstrap/pom.xml gateway-bootstrap/
COPY gateway-manifest-cli/pom.xml gateway-manifest-cli/
COPY gateway-contract-schema/pom.xml gateway-contract-schema/
COPY gateway-example/pom.xml gateway-example/
COPY gateway-example/test-provider/pom.xml gateway-example/test-provider/
COPY gateway-example/test-client/pom.xml gateway-example/test-client/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn -pl gateway-bootstrap -am clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home gateway
COPY --from=builder --chown=gateway:gateway /build/gateway-bootstrap/target/gateway-bootstrap-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
