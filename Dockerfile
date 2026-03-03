FROM maven:3.9.6-eclipse-temurin-21
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean compile

ENTRYPOINT ["mvn", "exec:java", "-Dexec.mainClass=com.raft.main"]