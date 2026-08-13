# Stage 1: Build
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build
COPY pom.xml .
COPY gateway-domain/pom.xml gateway-domain/
COPY gateway-application/pom.xml gateway-application/
COPY gateway-adapter-web/pom.xml gateway-adapter-web/
COPY gateway-adapter-postgresql/pom.xml gateway-adapter-postgresql/
COPY gateway-adapter-llm-http/pom.xml gateway-adapter-llm-http/
COPY gateway-adapter-dubbo/pom.xml gateway-adapter-dubbo/
COPY gateway-adapter-rest/pom.xml gateway-adapter-rest/
COPY gateway-adapter-grpc/pom.xml gateway-adapter-grpc/
COPY gateway-bootstrap/pom.xml gateway-bootstrap/
COPY gateway-manifest-cli/pom.xml gateway-manifest-cli/
COPY gateway-contract-schema/pom.xml gateway-contract-schema/
COPY gateway-test-provider/pom.xml gateway-test-provider/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/gateway-bootstrap/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
