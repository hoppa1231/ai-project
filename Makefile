.PHONY: help test backend-test agent-test android-test compose-validate backend-run dev-up dev-down

help:
	@printf '%s\n' \
	  'make test              Run all local unit-test suites' \
	  'make backend-test      Test the canonical Ktor backend' \
	  'make agent-test        Test the node agent' \
	  'make android-test      Run Android JVM unit tests' \
	  'make compose-validate  Validate local Compose configuration' \
	  'make dev-up            Start PostgreSQL and backend' \
	  'make dev-down          Stop the local stack' \
	  'make backend-run       Run the backend outside Docker'

test: backend-test agent-test android-test

backend-test:
	cd backend && bash ./gradlew --no-daemon test

agent-test:
	cd node-agent && bash ../gradlew --no-daemon -p . test

android-test:
	cd android-client && bash ./gradlew --no-daemon testDebugUnitTest

compose-validate:
	docker compose config --quiet

backend-run:
	cd backend && bash ./gradlew --no-daemon run

dev-up:
	docker compose up --build -d

dev-down:
	docker compose down
