FROM eclipse-temurin:11-jdk

WORKDIR /opt/app/dcc

COPY target/dcc-1.0-SNAPSHOT.jar app.jar
COPY table_data_baseline.csv ./table_data_baseline.csv
COPY perf_data_extreme_unique_300000.csv ./perf_data_extreme_unique_300000.csv

RUN mkdir -p output
RUN mkdir -p perf-reports

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/app/dcc/app.jar"]
