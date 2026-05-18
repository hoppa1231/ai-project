FROM gradle:9.3.0-jdk21 AS build
WORKDIR /app
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon clean installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/vpn-control-plane /app
EXPOSE 8080
CMD ["/app/bin/vpn-control-plane"]
