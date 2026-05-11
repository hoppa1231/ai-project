FROM gradle:9.3.0-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/vpn-node-agent/ ./
EXPOSE 9090
ENTRYPOINT ["./bin/vpn-node-agent"]
