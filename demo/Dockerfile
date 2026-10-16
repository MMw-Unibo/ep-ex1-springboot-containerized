# ---- Stage 1: build (JDK + Maven, thrown away afterwards) ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies before sources: this layer stays cached until pom.xml changes
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --layers --launcher --destination /layers

# ---- Stage 2: runtime (JRE only, non-root, layers ordered by change frequency) ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
EXPOSE 8080
# Size the heap from the container memory limit, not from the host RAM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
