# AWS EKS Infrastructure Automation with Terraform & Kubernetes

Spring Boot 기반 백엔드 애플리케이션입니다. EKS 내부에서 `backend-service`라는 ClusterIP Service로 실행되며, 프론트엔드 API 호출, S3 파일 업로드/미리보기, RDS MySQL 연결을 담당합니다.

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
- Terraform

## 전체 AWS 아키텍처
<img width="1500" height="1160" alt="image" src="https://github.com/user-attachments/assets/8a74258f-0771-4970-99c9-65ff97c9d086" />

```text
AWS Region: us-west-1

VPC 10.0.0.0/16
├─ Public Subnet 1  10.0.1.0/24   us-west-1b
│  ├─ Internet Gateway route
│  ├─ NAT Gateway
│  └─ Frontend LoadBalancer entry point
├─ Public Subnet 2  10.0.2.0/24   us-west-1c
│  └─ Internet Gateway route
├─ Private Subnet 1 10.0.10.0/24  us-west-1b
│  ├─ EKS managed node group
│  ├─ Frontend pods
│  ├─ Backend pods
│  └─ RDS subnet group
└─ Private Subnet 2 10.0.20.0/24  us-west-1c
   ├─ EKS managed node group
   ├─ Frontend pods
   ├─ Backend pods
   └─ RDS subnet group

External services
├─ ECR: backend/frontend container images
├─ S3: uploaded files
├─ IAM/OIDC: IRSA for backend S3 access
└─ GitHub Actions: CI/CD runner
```

```mermaid
flowchart TB
  user["User Browser"]
  gh["GitHub Actions"]

  subgraph aws["AWS us-west-1"]
    subgraph vpc["VPC 10.0.0.0/16"]
      igw["Internet Gateway"]

      subgraph publicSubnets["Public Subnets"]
        pub1["Public Subnet 1 - 10.0.1.0/24 - us-west-1b"]
        pub2["Public Subnet 2 - 10.0.2.0/24 - us-west-1c"]
        nat["NAT Gateway"]
        nlb["Frontend Service - LoadBalancer"]
      end

      subgraph privateSubnets["Private Subnets"]
        nodes["EKS Managed Node Group - t3.medium desired 2"]
        fe["Frontend Pods - replicas 2"]
        be["Backend Pods - replicas 2"]
        besvc["backend-service - ClusterIP 8080"]
        rds["RDS MySQL 8.0 - private only"]
      end
    end

    ecr["ECR - backend and frontend"]
    s3["S3 Bucket - private"]
    oidc["IAM OIDC Provider"]
    role["backend-sa IAM Role"]
  end

  user -->|"HTTP 80"| nlb
  nlb --> fe
  fe -->|"API requests"| besvc
  besvc --> be
  be -->|"JDBC 3306"| rds
  be -->|"AWS SDK"| s3
  be --> role
  role --> oidc
  role --> s3
  gh -->|"docker push"| ecr
  gh -->|"kubectl apply"| nodes
  nodes -->|"pull image"| ecr
  nodes -->|"outbound traffic"| nat
  nat --> igw
```

## 서브넷 구조

| 구분 | CIDR | AZ | 주요 배치 | 외부 통신 |
| --- | --- | --- | --- | --- |
| Public subnet 1 | `10.0.1.0/24` | `us-west-1b` | NAT Gateway, LoadBalancer 진입점 | Internet Gateway |
| Public subnet 2 | `10.0.2.0/24` | `us-west-1c` | LoadBalancer 가용 영역 | Internet Gateway |
| Private subnet 1 | `10.0.10.0/24` | `us-west-1b` | EKS nodes, pods, RDS subnet group | NAT Gateway |
| Private subnet 2 | `10.0.20.0/24` | `us-west-1c` | EKS nodes, pods, RDS subnet group | NAT Gateway |

EKS managed node group은 private subnet에 배치됩니다. 프론트엔드와 백엔드 Pod도 해당 노드 위에서 실행됩니다. RDS는 public 접근이 차단된 private DB로 구성되며, private subnet CIDR에서 MySQL `3306` 접근만 허용합니다.

## 프론트엔드 아키텍처

프론트엔드는 별도 repository인 `sample-app/front`에서 관리됩니다.

- Kubernetes `Deployment`: `frontend`
- Kubernetes `Service`: `frontend-service`
- Service type: `LoadBalancer`
- Container port: `80`
- Replicas: `2`
- 배포 이미지: `${FRONTEND_IMAGE}`

```mermaid
flowchart LR
  browser["Browser"] -->|"HTTP 80"| lb["frontend-service - LoadBalancer"]
  lb --> pod1["frontend pod"]
  lb --> pod2["frontend pod"]
  pod1 -->|"API request"| backend["backend-service - ClusterIP 8080"]
  pod2 -->|"API request"| backend
```

프론트엔드는 외부 사용자의 진입점입니다. EKS에서 `LoadBalancer` 타입 Service로 노출되며, AWS Load Balancer를 통해 브라우저 트래픽을 받습니다.

## 백엔드 아키텍처

백엔드는 이 repository에서 관리됩니다.

- Kubernetes `ServiceAccount`: `backend-sa`
- Kubernetes `Secret`: `backend-secret`
- Kubernetes `Deployment`: `backend`
- Kubernetes `Service`: `backend-service`
- Service type: `ClusterIP`
- Container port: `8080`
- Replicas: `2`
- 배포 이미지: `${BACKEND_IMAGE}`

```mermaid
flowchart TB
  fesvc["Frontend Pods"] -->|"API requests"| svc["backend-service - ClusterIP 8080"]
  svc --> b1["backend pod 1 - Spring Boot"]
  svc --> b2["backend pod 2 - Spring Boot"]

  b1 -->|"DB_URL and JDBC"| db["RDS MySQL"]
  b2 -->|"DB_URL and JDBC"| db
  b1 -->|"S3Client"| s3["S3 Bucket"]
  b2 -->|"S3Client"| s3

  sa["ServiceAccount backend-sa"] --> b1
  sa --> b2
  sa --> iam["IAM Role - S3 permissions"]
  iam --> s3
```

백엔드는 외부에 직접 노출되지 않습니다. EKS 내부 DNS인 `backend-service.sample-app.svc.cluster.local:8080`로만 접근합니다.

## 현재 배포 구조

```mermaid
sequenceDiagram
  participant Dev as Developer
  participant GH as GitHub Actions
  participant ECR as Amazon ECR
  participant EKS as Amazon EKS
  participant K8S as Kubernetes API

  Dev->>GH: git push main
  GH->>GH: Gradle build
  GH->>GH: Docker build
  GH->>ECR: Push image tagged with Git SHA
  GH->>EKS: aws eks update-kubeconfig
  GH->>K8S: kubectl apply namespace
  GH->>K8S: Render backend manifest and apply
  K8S->>EKS: RollingUpdate backend Deployment
  EKS->>ECR: Pull backend image
```

배포는 GitHub Actions의 `.github/workflows/deploy-eks.yml`에서 수행합니다.

1. `main` 브랜치 push 또는 수동 실행으로 workflow가 시작됩니다.
2. Gradle로 jar를 빌드합니다.
3. Docker 이미지를 빌드하고 ECR에 `${github.sha}`와 `latest` 태그로 push합니다.
4. EKS kubeconfig를 갱신합니다.
5. `k8s/backend.yaml`을 `envsubst`로 치환한 뒤 Kubernetes에 적용합니다.
6. `deployment/backend` rollout 완료를 기다립니다.

## 주요 AWS 리소스

| 리소스 | 이름/구성 | 역할 |
| --- | --- | --- |
| VPC | `sample-app-vpc`, `10.0.0.0/16` | 전체 네트워크 경계 |
| Public subnets | `10.0.1.0/24`, `10.0.2.0/24` | 외부 진입 LoadBalancer, NAT Gateway |
| Private subnets | `10.0.10.0/24`, `10.0.20.0/24` | EKS nodes, application pods, RDS |
| EKS | `sample-app-eks` | Kubernetes cluster |
| Node group | `sample-app-node-group` | private subnet의 worker nodes |
| ECR backend | `sample-app/backend` | 백엔드 컨테이너 이미지 저장소 |
| ECR frontend | `sample-app/frontend` | 프론트엔드 컨테이너 이미지 저장소 |
| RDS | `sample-app-mysql`, MySQL 8.0 | 애플리케이션 DB |
| S3 | `sample-app-dev-files-<account-id>-mj` | 업로드 파일 저장소 |
| IAM OIDC | EKS OIDC provider | IRSA 연동 |
| IAM Role | `sample-app-backend-sa-role` | 백엔드 Pod의 S3 접근 권한 |

## 통신 흐름

```text
User Browser
  |
  | HTTP
  v
Frontend Service (EKS LoadBalancer)
  |
  | Kubernetes Service routing
  v
Frontend Pods
  |
  | /api/* request
  v
Backend Service: backend-service.sample-app.svc.cluster.local:8080
  |
  | Kubernetes Service routing
  v
Backend Pods (Spring Boot)
  |                         |
  | JDBC                    | AWS SDK for Java v2
  v                         v
RDS MySQL (private subnet)  S3 Bucket
```

## 주요 API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/health` | Kubernetes readiness/liveness probe |
| `GET` | `/api/hello` | 백엔드 연결 확인 |
| `POST` | `/api/upload` | multipart 파일을 S3에 업로드 |
| `GET` | `/api/preview/{fileName}` | S3 객체를 스트리밍 방식으로 미리보기 |

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

## EKS 배포 변수

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
