# Projet Genetic Evolution

Application complète (Spring Boot + Angular) pour la recherche d'une fonction polynomiale de degré ≤ 3 qui passe par un maximum de points fournis à l'aide d'un algorithme génétique. L'ensemble est conteneurisé (Docker) et orchestré avec Docker Compose.

## Architecture

- **backend/** : service Spring Boot exposant les API pour gérer les points, configurer/lancer l'algorithme et diffuser les générations (REST + SSE).
- **frontend/** : application Angular présentant les blocs UI demandés (configuration, visualisation temps réel, fonctions candidates, évolution de la fitness, panneau récapitulatif).
- **docker-compose.yml** : orchestre les deux services.

## Lancer en local

### Prérequis
- Docker & Docker Compose

```bash
docker compose up --build
```

UI accessible sur `http://localhost:4200`, backend sur `http://localhost:8080`.

## Développement

### Backend
```
cd backend
./mvnw spring-boot:run # si Maven wrapper installé
# sinon mvn spring-boot:run
```

### Frontend
```
cd frontend
# requis : Node.js 22.x
npm install
npm start
```
Le serveur Angular utilise `proxy.conf.json` pour rediriger `/api` vers `localhost:8080`.

## Fonctionnalités principales
- Paramétrage complet de l'algorithme (population, mutation, crossover, élitisme, tolérance, délai entre générations).
- Commandes start/stop et sauvegarde des points.
- Visualisation canvas des points avec la fonction optimale rafraîchie à chaque génération + export PNG.
- Liste des meilleures fonctions de chaque génération et graphe de la fitness optimale.
- Panneau récapitulatif en bas à droite affichant en temps réel les paramètres et détails de la meilleure fonction.
