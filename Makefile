# Syncra HIMS — developer shortcuts. Only Docker is required on the workstation.
.DEFAULT_GOAL := help
COMPOSE := docker compose

.PHONY: help env up up-prod down ps logs config test test-gateway test-patient test-iam test-pharmacy test-billing test-abdm test-ai test-web test-db seed-dev seed-demo web-dev token

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

env: ## Create .env with random secrets (once)
	./scripts/init-env.sh

up: ## Build and start the stack with dev loopback ports
	$(COMPOSE) up -d --build

up-prod: ## Build and start without dev overrides (hospital server)
	$(COMPOSE) -f docker-compose.yml up -d --build

down: ## Stop the stack (volumes are kept)
	$(COMPOSE) down

ps: ## Container status and health
	$(COMPOSE) ps

logs: ## Follow logs of all services
	$(COMPOSE) logs -f --tail=200

config: ## Validate and print the resolved compose configuration
	$(COMPOSE) config --quiet && echo "compose configuration OK"

test: test-gateway test-patient test-iam test-pharmacy test-billing test-abdm test-ai test-web ## Run all test suites in containers

test-gateway: ## Gateway tests (Maven in Docker)
	docker run --rm -v "$(CURDIR)/services/api-gateway":/workspace -v syncra-m2:/root/.m2 \
	  -w /workspace maven:3.9-eclipse-temurin-21 mvn -B -q verify

test-patient: ## Patient service tests (Maven in Docker; Testcontainers starts a sibling Postgres)
	docker run --rm -v "$(CURDIR)":/repo -v syncra-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
	  --add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	  -w /repo maven:3.9-eclipse-temurin-21 sh -c "mvn -B -q -f libs/hims-common/pom.xml install && \
	  mvn -B -q -f services/patient-service/pom.xml verify"

test-iam: ## IAM service tests (Maven in Docker; Testcontainers starts a sibling Postgres)
	docker run --rm -v "$(CURDIR)":/repo -v syncra-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
	  --add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	  -w /repo maven:3.9-eclipse-temurin-21 sh -c "mvn -B -q -f libs/hims-common/pom.xml install && \
	  mvn -B -q -f services/iam-service/pom.xml verify"

test-pharmacy: ## Pharmacy service tests (Maven in Docker; Testcontainers starts a sibling Postgres)
	docker run --rm -v "$(CURDIR)":/repo -v syncra-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
	  --add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	  -w /repo maven:3.9-eclipse-temurin-21 sh -c "mvn -B -q -f libs/hims-common/pom.xml install && \
	  mvn -B -q -f services/pharmacy-service/pom.xml verify"

test-billing: ## Billing service tests (Maven in Docker; Testcontainers starts a sibling Postgres)
	docker run --rm -v "$(CURDIR)":/repo -v syncra-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
	  --add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	  -w /repo maven:3.9-eclipse-temurin-21 sh -c "mvn -B -q -f libs/hims-common/pom.xml install && \
	  mvn -B -q -f services/billing-service/pom.xml verify"

test-abdm: ## ABDM service tests (Maven in Docker; Testcontainers Postgres + Redis, simulated ABDM gateway/HIU)
	docker run --rm -v "$(CURDIR)":/repo -v syncra-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock \
	  --add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
	  -w /repo maven:3.9-eclipse-temurin-21 sh -c "mvn -B -q -f libs/hims-common/pom.xml install && \
	  mvn -B -q -f services/abdm-service/pom.xml verify"

test-ai: ## AI service tests (pytest in Docker)
	docker run --rm -e PYTHONDONTWRITEBYTECODE=1 -v "$(CURDIR)/services/ai-service":/src:ro \
	  -w /src python:3.12-slim sh -c "pip install -q -r requirements-dev.txt && python -m pytest -p no:cacheprovider"

test-web: ## Doctor Desk type-check, unit/UI tests and production build (Node in Docker)
	docker run --rm -v "$(CURDIR)/frontend/doctor-desk":/app -v syncra-npm:/root/.npm -w /app node:22-alpine \
	  sh -c "npm ci --no-audit --no-fund && npm run typecheck && npm test && npm run build"

web-dev: ## Doctor Desk dev server with hot reload on http://localhost:5173 (stack must be up)
	docker run --rm -it --network syncra-app -p 127.0.0.1:5173:5173 -v "$(CURDIR)/frontend/doctor-desk":/app \
	  -v syncra-npm:/root/.npm -w /app -e SYNCRA_GATEWAY_URL=http://api-gateway:8080 node:22-alpine \
	  sh -c "npm ci --no-audit --no-fund && npx vite --host 0.0.0.0 --port 5173"

test-db: ## Schema smoke test as the app role (needs postgres running; rolled back)
	$(COMPOSE) exec -T postgres sh -c 'PGPASSWORD="$$SYNCRA_APP_DB_PASSWORD" psql -h 127.0.0.1 \
	  -U "$$SYNCRA_APP_DB_USER" -d "$$POSTGRES_DB" --no-psqlrc -q -f /syncra/database/tests/schema-smoke-test.sql'

seed-demo: ## Load a demo OPD morning through the API (development only; after seed-dev)
	./scripts/seed-demo.sh

seed-dev: ## Add sample staff matching dev-token subjects (development only)
	@grep -q '^SYNCRA_ENVIRONMENT=production' .env && { echo "refusing: production environment"; exit 1; } || true
	$(COMPOSE) exec -T postgres sh -c 'psql -U "$$POSTGRES_USER" -d "$$POSTGRES_DB" --no-psqlrc -q \
	  -f /syncra/database/dev/seed-dev-staff.sql'

token: ## Print a dev JWT for calling the gateway
	@python3 scripts/dev-token.py
