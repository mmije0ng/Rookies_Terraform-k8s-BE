# Sample App Backend

Spring Boot 기반 백엔드 애플리케이션입니다. EKS 내부에서 `backend-service`라는 ClusterIP Service로 실행되며, 프론트엔드에서 호출하는 API, S3 파일 업로드/미리보기 API, RDS MySQL 연결 설정을 포함합니다.

## 기술 스택

- Java 17
- Spring Boot 3.2.5
- Spring Web
- Spring Data JPA
- MySQL Connector/J
- AWS SDK for Java v2 S3
- Docker
- Kubernetes/EKS
- GitHub Actions

## 통신 구조

```text
User Browser
  |
  | HTTP
  v
Frontend Service (EKS LoadBalancer/NLB)
  |
  | /api/* 요청
  v
Backend Service: backend-service.sample-app.svc.cluster.local:8080
  |
  | Kubernetes Service 라우팅
  v
Backend Pods (Spring Boot, private subnet)
  |                         |
  | JDBC                    | AWS SDK for Java v2
  v                         v
RDS MySQL (private subnet)  S3 Bucket
                            ^
                            |
                    IRSA ServiceAccount
                    backend-sa -> IAM Role
```

### 네트워크 흐름

- 외부 사용자는 프론트엔드 LoadBalancer를 통해 애플리케이션에 접근합니다.
- 프론트엔드는 백엔드 API를 호출합니다.
- 백엔드는 EKS 내부 `ClusterIP` 서비스인 `backend-service`로만 노출됩니다.
- 백엔드 Pod는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경변수를 사용해 RDS MySQL에 접속합니다.
- 백엔드 Pod는 `backend-sa` ServiceAccount와 IRSA로 연결된 IAM Role을 통해 S3에 접근합니다.
- S3 버킷 이름은 `S3_BUCKET_NAME` 환경변수로 전달됩니다.

## 설정 파일

### `src/main/resources/application.yml`

Spring Boot 설정 파일입니다.

- `.env` 파일을 선택적으로 import합니다.
- `spring.datasource.*`는 DB 환경변수를 사용합니다.
- `spring.jpa.database-platform`은 MySQL dialect를 명시합니다.
- `cloud.aws.region`, `cloud.aws.s3.bucket`은 S3 클라이언트 설정에 사용됩니다.

### `.env`

로컬 실행용 환경변수 파일입니다. Git에는 커밋하지 않습니다.

```env
AWS_REGION=us-west-1

DB_URL=jdbc:mysql://<rds-endpoint>:3306/mydb?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
DB_USERNAME=<db-username>
DB_PASSWORD=<db-password>

S3_BUCKET_NAME=<s3-bucket-name>
BACKEND_IMAGE=<ecr-backend-image>
BACKEND_SA_ROLE_ARN=<backend-service-account-role-arn>
```

`DB_URL`은 반드시 `jdbc:mysql://`로 시작해야 합니다. `mysql://` 형식은 Spring JDBC URL로 인식되지 않습니다.

## 로컬 실행

```bash
./gradlew clean build -x test
./gradlew bootRun
```

Windows PowerShell에서는 다음처럼 실행할 수 있습니다.

```powershell
.\gradlew.bat clean build -x test
.\gradlew.bat bootRun
```

로컬에서 RDS에 직접 접속하려면 네트워크 경로와 보안 그룹이 허용되어 있어야 합니다. RDS가 private subnet에만 있으면 로컬 PC에서 직접 접속되지 않을 수 있습니다.

## Docker 빌드

```bash
./gradlew clean build -x test
docker build -t sample-app-backend:local .
docker run --env-file .env -p 8080:8080 sample-app-backend:local
```

## EKS 배포 구조

Kubernetes 리소스는 `k8s/backend.yaml`에 정의되어 있습니다.

- `ServiceAccount`: `backend-sa`
- `Secret`: `backend-secret`
- `Deployment`: `backend`
- `Service`: `backend-service`

배포 시 GitHub Actions가 다음 값을 주입합니다.

| 변수 | 사용처 | 값 출처 |
| --- | --- | --- |
| `BACKEND_IMAGE` | Deployment image | ECR 이미지 URI + Git SHA |
| `BACKEND_SA_ROLE_ARN` | ServiceAccount IRSA annotation | Terraform output |
| `AWS_REGION` | 애플리케이션/S3 region | GitHub Actions variable |
| `DB_URL` | Spring datasource URL | `terraform output -raw rds_db_url` |
| `DB_USERNAME` | Spring datasource username | Terraform DB username |
| `DB_PASSWORD` | Spring datasource password | Terraform DB password |
| `S3_BUCKET_NAME` | S3 bucket 설정 | `terraform output -raw s3_bucket_name` |

## GitHub Actions Secrets

백엔드 repo에는 다음 Secrets가 필요합니다.

```text
AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY
ECR_BACKEND_URI
EKS_CLUSTER_NAME
BACKEND_SA_ROLE_ARN
DB_URL
DB_USERNAME
DB_PASSWORD
S3_BUCKET_NAME
```

`AWS_REGION`은 repository variable로 설정할 수 있습니다. 설정하지 않으면 workflow 기본값인 `us-west-1`을 사용합니다.

## 배포 흐름

```text
git push main
  |
  v
GitHub Actions
  |
  | Gradle build
  | Docker build
  | Push image to ECR
  | aws eks update-kubeconfig
  | envsubst < k8s/backend.yaml
  v
EKS rolling update
```

## 운영 확인 명령

```bash
kubectl get all -n sample-app
kubectl get pods -n sample-app -l app=backend
kubectl logs -n sample-app -l app=backend --tail=100
kubectl describe pod -n sample-app -l app=backend
kubectl rollout status deployment/backend -n sample-app
```

서비스 내부 DNS:

```text
backend-service.sample-app.svc.cluster.local:8080
```

## 주요 API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/health` | Kubernetes readiness/liveness probe |
| `GET` | `/api/hello` | 백엔드 연결 확인 |
| `POST` | `/api/upload` | multipart 파일을 S3에 업로드 |
| `GET` | `/api/preview/{fileName}` | S3 객체를 스트리밍 방식으로 미리보기 |