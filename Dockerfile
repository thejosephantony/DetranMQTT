FROM maven:3.9.11-eclipse-temurin-17 AS testes
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B package
CMD ["mvn", "-B", "test"]

FROM eclipse-temurin:17-jre-jammy
RUN useradd --system --user-group --uid 10001 app && mkdir -p /app /dados && chown app:app /dados
WORKDIR /app
COPY --from=testes /build/target/detran.jar /app/detran.jar
ENV DATA_DIR=/dados
USER app
ENTRYPOINT ["java", "-Xms32m", "-Xmx128m", "-Dfile.encoding=UTF-8", "-Duser.timezone=America/Fortaleza", "-jar", "/app/detran.jar"]
CMD ["--help"]
