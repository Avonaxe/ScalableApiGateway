pipeline {
    agent any

    // Optional: Define environment variables if needed
    environment {
        APP_NAME = "api-gateway"
    }

    tools {
        // These names must match what you name them in Jenkins > Global Tool Configuration
        // If you are running Jenkins locally with Maven/Java installed on the host, you might not need this block.
        maven 'Maven' 
        jdk 'Java21'
    }

    stages {
        stage('Checkout') {
            steps {
                echo 'Pulling source code from GitHub...'
                checkout scm
            }
        }

        stage('Build & Unit Test') {
            steps {
                echo 'Compiling Java code and running unit tests...'
                // Skip tests here if you only want to rely on the bash script later, 
                // but running unit tests first is best practice.
                sh 'mvn clean package'
            }
        }

        stage('Build Container Images') {
            steps {
                echo 'Building Docker image for API Gateway...'
                sh 'docker compose build'
            }
        }

        stage('Deploy (Local Docker)') {
            steps {
                echo 'Deploying the stack via Docker Compose...'
                sh 'docker compose down'
                sh 'docker compose up -d'
            }
        }

        stage('Integration & E2E Testing') {
            steps {
                echo 'Waiting for services to initialize (15 seconds)...'
                sh 'sleep 15'
                
                echo 'Running automated verification suite...'
                sh 'chmod +x test_application.sh'
                sh './test_application.sh'
            }
        }
    }

    post {
        success {
            echo '✅ Pipeline succeeded! API Gateway is live, tested, and fully functional!'
        }
        failure {
            echo '❌ Pipeline failed! Check the logs above.'
            // Optional: Shut down the broken stack if the test fails
            // sh 'docker compose down'
        }
    }
}