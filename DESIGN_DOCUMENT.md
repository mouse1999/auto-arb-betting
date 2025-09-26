# 🎯 LIVE ARBITRAGE AUTOMATION BOT

---

## 📝 User Story
As a **sports bettor leveraging arbitrage opportunities**,  
I want an **automated backend system** that detects, executes, and manages bets across multiple bookmakers,  
So that I can **maximize profits securely** while reducing the risk of detection or failed executions.

---

## 🚀 Key Features

### 📡 Live Odds Integration
Connect to real-time odds data using **Playwright** for the three bookmakers:
- SportyBet
- Bet9ja
- Msport

---

### 🔎 Arbitrage Detection
Continuously detect arbitrage opportunities across these bookmakers in real time.

---

### 🤖 Smart Bet Execution
- Place bets only if sufficient balances are available on both bookmakers.
- Slightly round bet amounts to avoid detection by bookmakers’ anti-arbitrage systems.  
  This will add to covering up of arbing activities to safeguard the account, though not always guaranteed.

---

### 📊 Monitoring & Dashboard
A centralized dashboard should display:
- Active arbitrage opportunities
- Executed bets and their outcomes
- Failed executions (with reasons)
- Account balances per bookmaker

---

### ♻️ Failure Handling
If one side of an arbitrage bet fails (e.g., rejected or not placed):
- Actively monitor that game and its odds in real time.
- Retry bet placement when current odds become favorable again to recover the arbitrage position.

---
## ✅ Acceptance Criteria

---

### ⚡ Live Odds Latency
The system gets live odds feeds from **SportyBet**, **Bet9ja**, and **Msport** with less than *1.5 seconds latency*.

---

### 🎯 Arbitrage Detection
The system flags and logs arbitrage opportunities in real time.

---

### 💰 Fund Validation
Bets are only placed when sufficient funds are available in the relevant bookmakers.

---

### 🔄 Bet Amount Rounding
Bet amounts are rounded using a configurable rule before submission.

---

### 📝 Execution Logging
All executions (success or failure) are logged with accurate timestamps.

---

### 📊 Dashboard Metrics
The dashboard displays real-time metrics, including:
- Balances per bookmaker
- Bet history
- Current arbitrage opportunities

---

### ♻️ Failure Recovery (Retry Mode)
If a bet leg fails, the system enters a **retry mode**, where it continuously monitors the odds and automatically re-triggers bet placement when conditions are favorable.

---
## 🛠️ Tech Stack

Alright, so here's the tech stack I've decided on for the project. Right now,
I'm keeping my focus tight to deliver a solid Minimum Viable Product (MVP). Some of these tools might not get fully utilized until we start looking at scaling things up,
but I'm listing them now so we know where we're headed.

---

### ⚙️ Core Backend
- **Java 17+** → Primary programming language.
- **Spring Boot** → Main framework for building the backend services.
- **Spring Web (REST API)** → Expose APIs for odds, arbitrage detection, bets, and dashboard.

---

### 🤖 Automation & Bookmaker Integration
- **Playwright for Java** → Browser automation for login, odds scraping, and bet placement.

---

### 🔐 Security & Config
- **Spring Security** → Secure APIs and dashboard access.
- **Spring Cloud Config** → Centralized configuration management (for scaling/microservices).

---

### 💾 Data & Persistence
- **Spring Data JPA** →  data access layer.
- **PostgreSQL** → Relational database for persistent storage.
- **Redis (optional or can be implemented later)** → Fast in-memory cache for live odds & balances.

---

### 📊 Monitoring & Observability
- **Spring Boot Actuator** → Expose system health and metrics.
- **Micrometer + Prometheus + Grafana** → Collect and visualize metrics in a real-time dashboard.

---

### 🔁 Reliability & Retry
- **Spring Batch** → Manage scheduled tasks and retries for failed bet executions.
- **Spring Retry** → Retry logic for transient failures (e.g., odds changes, network issues).

---

### ☁️ Cloud & Scalability
- **Spring Cloud** (future, if microservices is considered):
  - Spring Cloud Stream → Event-driven communication (Kafka).
  - Spring Cloud Config → Centralized config for multiple services.

---

### 🧪 Testing & Quality
- **JUnit 5** → Unit and integration testing (TDD approach).
- **Mockito** → Mocking external dependencies in tests.
- **Testcontainers** → Spin up real DBs (Postgres, Redis) for integration tests.

---

### 🖥️ Frontend / Dashboard (Optional)
This might be optional, but it will be nice to implement this part to help non-technical users
- **React** → For building the live dashboard.
- **WebSockets / Server-Sent Events (SSE)** → Real-time updates for odds, bets, and metrics.
