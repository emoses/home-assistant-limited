FROM eclipse-temurin:17-jre-jammy

WORKDIR /

COPY target/server.jar server.jar
EXPOSE 8080

CMD java -jar server.jar
