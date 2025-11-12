COMPOSE ?= docker compose

.PHONY: up build down logs restart clean prune

up:
	$(COMPOSE) up --build -d

build:
	$(COMPOSE) build

rebuild:
	$(COMPOSE) build --no-cache

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f

restart: down up

clean:
	$(COMPOSE) down -v --remove-orphans

ps:
	while true; do clear; $(COMPOSE) ps; sleep 3; done

prune:
	docker system prune -f
	docker volume prune -f || true
