# Use a JDK image
FROM eclipse-temurin:21-jdk-jammy

# Set working directory
WORKDIR /app

# Copy only the built boot JAR
COPY api/build/libs/*.jar app.jar

# Expose the port
EXPOSE 8080

# Run the JAR
ENTRYPOINT ["java", "-jar", "app.jar"]