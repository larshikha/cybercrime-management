# AI-Enabled Cybercrime Complaint & Investigation Management (Phase 1 PoC)

Two Spring Boot microservices + a web UI. Everything in the Phase 1 brief is already implemented; the phases below are your **run-and-demo plan** for the 3-hour window.

```
Browser (index.html)  ->  complaint-service :8081  --REST-->  analysis-service :8082
                          (complaints, cases, evidence,        (AI engine: classifier,
                           transactions, alerts, dashboard,     priority scorer,
                           H2/JPA database, serves the UI)      related-case finder)
```

| Service | Port | Role |
|---|---|---|
| `complaint-service` | 8081 | System of record (JPA + H2), REST API, alerts, dashboard, static UI, loads the historical dataset |
| `analysis-service` | 8082 | Stateless AI engine. Receives a complaint + historical complaints, returns category, priority, related cases, patterns and the key factors |

**Prerequisites:** JDK 17+ and Maven 3.8+ (internet needed on first build to download dependencies).

---

## Phase 0: Setup and first run (target: 15 min)

Open two terminals in this folder.

```bash
# Terminal 1
cd analysis-service
mvn spring-boot:run

# Terminal 2
cd complaint-service
mvn spring-boot:run
```

Open **http://localhost:8081**. Both pills in the header should say `UP`.
Start `analysis-service` first. If you start it second, the pill turns green within 15 seconds.

Checklist
- [ ] Dashboard shows 138 historical complaints
- [ ] Both header pills are green
- [ ] `http://localhost:8082/api/health` returns `UP`

If the build fails, see **Troubleshooting** at the bottom.

---

## Phase 1: Data source and complaint management (target: 15 min to understand)

**Requirement:** complaint info with category, date, affected party, transaction/loss, status; historical data; database.

- **Data source:** `complaint-service/src/main/resources/data/historical_complaints.csv` has 138 simulated complaints in five categories (financial fraud, phishing, identity theft, account compromise, social-media abuse). They were generated with a script and contain realistic, **planted fraud rings** so related-case detection has something real to find:
  - UPI `quickrefund.support@okaxis` and beneficiary account `50200098761234` (OLX/QR refund scam, 6 complaints)
  - Domain `sbi-kyc-update.in` (KYC phishing, 5 complaints)
  - Phone `+919123456780` (sextortion, 4 complaints)
  - Domain `wealthgrow-invest.com` and account `60110045678901` (investment scam, 5 complaints)
  - IP `185.220.101.47` (account takeover, 4 complaints)
- `DataLoader.java` loads the CSV into the database on startup (complaints, transactions, evidence references, cases).
- **Database:** H2 in-memory via Spring Data JPA. Console at `http://localhost:8081/h2-console` (JDBC URL `jdbc:h2:mem:cybercrime`, user `sa`, empty password). To switch to MySQL, see the comment block in `complaint-service/src/main/resources/application.properties`.
- **Tables:** `complaints`, `txns`, `evidence`, `cases`, `case_activities`, `alerts`.

Demo this: **Complaints** tab -> search for `quickrefund` -> 6 hits.

---

## Phase 2: AI engine (analysis-service) (target: 20 min to understand)

Code: `analysis-service/src/main/java/com/cyber/analysis/`

| Capability | Class | How it works |
|---|---|---|
| Classification | `ClassifierService` | Hybrid: weighted keyword lexicon (explainable) blended 60/40 with a Multinomial Naive Bayes model trained on the historical complaints at request time. Returns category, confidence, per-category scores and the matched keywords |
| Prioritisation | `PriorityService` | Transparent additive risk score (0-100): loss amount tiers, category severity, vulnerable victim, ongoing coercion, golden-hour window, and links to earlier cases. Every point is returned as a readable factor. Levels: LOW <30, MEDIUM 30-49, HIGH 50-69, CRITICAL 70+ |
| Related cases | `RelatedCaseService` | Shared UPI / phone / email / domain / IP / beneficiary account (hard evidence) + TF-IDF cosine similarity of the narrative (soft evidence) + same category and time proximity. Returns top matches with the exact factors, plus recurring-pattern statements and 14-day spikes |

Why this satisfies "not a generic AI response": every output is computed from the complaint and transaction data and compared with historical data, and the response lists the evidence behind each conclusion.

Quick test of the engine alone:
```bash
curl -s -X POST localhost:8082/api/analyze -H "Content-Type: application/json" -d '{
 "complaint":{"description":"Buyer on OLX sent a QR code, Rs 40000 debited via UPI","incidentDate":"2026-10-10","amountLost":40000,"suspectUpi":"quickrefund.support@okaxis"},
 "history":[]}'
```

> Honest note for judges: the classifier scored 100% in leave-one-out testing, but that is on **templated simulated data**, so do not present it as real-world accuracy. Real deployment needs real labelled data (for example NCRP exports) and evaluation. This is also your "future work" slide.

---

## Phase 3: Inter-service integration and end-to-end flow (target: 20 min)

Code: `complaint-service/.../ComplaintService.java` and `AnalysisClient.java`

Flow when an officer registers a complaint:

1. `POST /api/complaints` saves the complaint and transaction in the database.
2. `ComplaintService.analyze()` gathers the complaint and all historical complaints and calls `POST http://localhost:8082/api/analyze` (REST, Spring `RestClient`).
3. The result is stored on the complaint (category, priority, score, full analysis JSON).
4. Alerts are generated: HIGH_PRIORITY, RELATED_CASES, RECURRING_PATTERN.
5. The UI shows classification, priority factors, related cases with factors and recommended actions.
6. The officer creates a case, assigns an investigator, records activities and evidence, and can escalate.

If `analysis-service` is down, the complaint is still saved and the UI says AI is unavailable.

---

## Phase 4: Web UI walkthrough (target: 15 min to rehearse)

All in `complaint-service/src/main/resources/static/index.html` (plain HTML/JS, no build step).

| Tab | Shows |
|---|---|
| Dashboard | KPIs, complaints and loss by category, priority distribution, case progress, monthly trend, recurring identifiers, latest high-priority complaints |
| Complaints | Search by number, name, UPI, phone, email, URL, account, IP or text; filter by category, priority, status; click a row for the full detail drawer |
| Register Complaint | Form with identifiers and transaction; category can be left on Auto-detect |
| Cases | All investigation cases |
| Alerts | High-priority, related-case, recurring-pattern, escalation and stalled-case alerts with Resolve |
| Fraud Monitoring | UPI IDs, accounts, phones, domains, emails and IPs seen in 2+ complaints (mule account and ring detection) |
| Officer Query | The brief's example question, answered with evidence |

---

## Phase 5: The demo script (target: 10 min to rehearse, 5 min to present)

1. **Dashboard** (30s): total volume, loss, category split, recurring identifiers.
2. **Register Complaint** -> click **Fill demo OLX/QR scam** -> **Register & run AI analysis**.
3. In the drawer, narrate:
   - Classified as ONLINE FINANCIAL FRAUD, with confidence and the matched keywords (upi, qr code, olx, debited).
   - Priority CRITICAL (about 78/100), with each factor listed.
   - Related cases: 5 earlier complaints linked through the same UPI ID and the same beneficiary account, with the exact factors.
   - Patterns: the UPI ID appears in 6 earlier complaints with total loss shown.
   - Recommended actions (freeze request within golden hour, consolidate cases, etc.).
4. **Create case & assign**, add an activity, click **Escalate**, then show the new alert in **Alerts**.
5. **Officer Query** -> **Analyse**: "Are these newly reported online financial-fraud complaints related to previously reported cases?"
6. **Fraud Monitoring**: show the recurring beneficiary accounts.
7. Close with the architecture diagram above and the future-work slide.

### Requirement checklist (tick these on your submission)

| Mandatory requirement | Where |
|---|---|
| Web interface to register and search complaints | UI tabs Register Complaint and Complaints |
| Complaint info (category, date, affected party, transaction/loss, status) | `Complaint`, `Txn` entities and the register form |
| Historical or simulated data | `historical_complaints.csv` (138 rows) |
| AI classification / prioritisation / related-case | `analysis-service` |
| At least one data source integrated | CSV dataset loaded by `DataLoader` |
| Spring Boot API layer | `ComplaintController`, `AnalysisController` |
| Two Java + Spring Boot microservices with REST between them | `complaint-service` -> `analysis-service` via `AnalysisClient` |
| Database integration | JPA + H2 (MySQL switch documented) |
| Display classification/recommendation and key factors | Complaint detail drawer |
| End-to-end flow, registration to AI-assisted investigation support | Phase 3 and demo script |

---

## Phase 6: Buffer, polish and submission (target: last 30 min)

- [ ] Rehearse the demo script once, start to finish
- [ ] Take screenshots: dashboard, AI analysis drawer, officer query result, fraud monitoring
- [ ] Zip the project or push to GitHub, and put the Phase 5 table in your report
- [ ] Prepare 3 sentences on future work: real NCRP data, a trained ML model with evaluation, authentication and role-based access (Spring Security), audit logging, and moving to a message queue between services

---

## REST API reference

`complaint-service` (8081)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/complaints?q=&category=&status=&priority=` | Search |
| POST | `/api/complaints` | Register and run AI analysis |
| GET | `/api/complaints/{id}` | Full detail with analysis and recommended actions |
| POST | `/api/complaints/{id}/analyze` | Re-run AI |
| POST | `/api/complaints/{id}/transactions` | Add transaction |
| POST | `/api/complaints/{id}/evidence` | Add evidence reference |
| POST | `/api/complaints/{id}/case` | Open case and assign investigator |
| GET | `/api/cases` | List cases |
| POST | `/api/cases/{id}/activities` | Record activity |
| PUT | `/api/cases/{id}/status` | OPEN / IN_PROGRESS / ESCALATED / CLOSED |
| GET | `/api/alerts` | Open alerts |
| POST | `/api/alerts/{id}/resolve` | Resolve alert |
| GET | `/api/monitoring/clusters` | Recurring identifiers and accounts |
| GET | `/api/dashboard` | Dashboard aggregates |
| GET | `/api/insights/new-fraud-links?days=7` | The officer query |

`analysis-service` (8082): `POST /api/analyze`, `GET /api/health`

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `release version 17 not supported` | Install JDK 17 or newer. Check with `java -version` |
| Dependencies will not download | Check internet or proxy. Spring Boot 3.3.5 needs Maven Central |
| `Port 8081/8082 already in use` | Stop the other process, or change `server.port` in the service's `application.properties` (and `analysis.service.url` in complaint-service) |
| Analysis pill is red | Start `analysis-service`. Complaints still register without it |
| Blank dashboard | Check the complaint-service log for `Loaded 138 historical complaints` |
| Compile error you cannot fix | Copy the exact message to me and I will fix it |

## Project layout
```
cybercrime-poc/
  pom.xml
  analysis-service/   (Spring Boot, port 8082)
  complaint-service/  (Spring Boot, port 8081, static UI, CSV data)
```
