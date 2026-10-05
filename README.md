# Software Oracle

Software Oracle is an AI-powered tool that allows you to ask questions about your codebase in natural language. It uses a Neo4j graph database to store code structures and Git history, and leverages local LLMs (via Ollama) to translate natural language questions into Cypher queries and format the results into human-readable answers.

## Features

- **Code Analysis**: Analyzes Java source code to build a graph of types, methods, packages, and their relationships.
- **Git Integration**: Imports Git history, including tags, releases, churn data (insertions/deletions), and co-change coupling.
- **Dependency Analysis**: Parses `pom.xml` to map external libraries and link them to the source code.
- **Issue Tracking**: Links Jira issues to commits (issue keys in messages) and reconstructs merge requests from merge commits, including which commits and issues each one merged. Works offline; with Jira API credentials the issues are enriched with summary, status, type and assignee.
- **Deep Correlation**: Automatically links Git commits to specific Java Methods and Types, and maps code elements to the libraries they use.
- **Natural Language Interface**: Ask questions like "Who modified this method last?" or "Which files are most volatile (hotspots)?"
- **Modern Web UI**: Simple and responsive interface built with Tailwind CSS.
- **Local & Private**: Runs entirely on your machine using Ollama and Neo4j.

## Prerequisites

- **Java 21**: The project uses modern Java features and requires JDK 21.
- **Maven**: For building and dependency management.
- **Neo4j**: A running Neo4j instance (version 5.x recommended).
- **Ollama**: For running the LLM locally.

## Setup

### 1. Neo4j
Ensure you have a Neo4j database running. You can run it via Docker:
```bash
docker run \
    --name neo4j \
    -p 7474:7474 -p 7687:7687 \
    -d \
    -e NEO4J_AUTH=neo4j/password \
    neo4j:latest
```

### 2. Ollama
Install Ollama and pull the required model (e.g., `llama3`):
```bash
ollama pull llama3
```

### 3. Configuration
Edit `src/main/resources/application.properties` to match your environment:
```properties
# Server Port
oracle.port=8080

# Ollama Settings
ollama.uri=http://localhost:11434
ollama.model=llama3
ollama.timeoutSeconds=180

# Neo4j Settings
neo4j.uri=bolt://localhost:7687
neo4j.username=neo4j
neo4j.password=password

# Repository to analyze
repo.path=/path/to/your/repo

# Active importers (comma-separated list of: git, java, maven, jira)
oracle.importers=git,java,maven

# Jira (optional — the jira importer builds Issue stubs and MergeRequest nodes
# from git history alone; with credentials it also enriches issues via the
# Jira REST API). jira.projectKeys restricts key matching to your projects.
jira.uri=https://yourcompany.atlassian.net
jira.email=you@example.com
jira.token=<api-token>
jira.projectKeys=ORACLE,PLATFORM
```

## Running the Application

### 1. Build the project
```bash
mvn clean install
```

### 2. Import Data
Before you can ask questions, you need to populate the graph database by running the batch importer. You can configure which importers are active in `application.properties`.
```bash
java -cp target/software-oracle-1.0-SNAPSHOT.jar:target/lib/* ml.SoftwareOracle
```
*(Note: Ensure all dependencies are in the classpath. If running from an IDE, simply run the `SoftwareOracle` main class.)*

### 3. Start the Server
Run the web server to start the UI and API:
```bash
java -cp target/software-oracle-1.0-SNAPSHOT.jar:target/lib/* ml.oracle.OracleServer
```

### 4. Access the UI
Open your browser and navigate to:
`http://localhost:8080`

## Evaluating NL→Cypher quality

An execution-accuracy harness measures how well the model translates questions
into Cypher. Each case in `src/main/resources/eval/cases.json` pairs a
natural-language question with a trusted *reference* query; the harness runs the
real pipeline and marks a case correct when the generated query returns the same
rows as the reference (comparison ignores column naming/order and — unless the
case is `orderSensitive` — row order, so any correctly-phrased query passes).

1. Import the **software-oracle repo itself** with all three importers
   (`oracle.importers=git,java,maven`, `repo.path` pointing at this checkout),
   so the graph the cases expect exists.
2. Run the harness (Ollama and Neo4j must be up):
   ```bash
   mvn -o compile exec:java -Dexec.mainClass=ml.oracle.eval.EvalRunner
   ```
3. It prints a per-case result line and an overall accuracy, and writes a full
   report to `target/eval-report.md` (including each generated query, so
   failures are diagnosable).

Add cases by appending to `cases.json` — no code changes needed. Re-run after
any prompt/schema/model change to check for regressions.

Each case may declare `"requires": [...]` — node labels that must exist in the
graph for the case to be meaningful (e.g. `["Type"]` for Java cases,
`["Module"]` for Maven ones). Cases whose labels are absent are reported as
SKIPPED and excluded from the accuracy score, so importer-specific cases (a
future JavaScript importer, say) can live in the same file and simply won't
count when evaluated against a repo or importer set that lacks them.

## Architecture

The application is structured into several modules:
- `ml.oracle.controller`: Handles HTTP requests (Javalin).
- `ml.oracle.service`: Contains the core logic for Cypher generation and answer formatting.
- `ml.oracle.infrastructure`: Integrations with Neo4j and Ollama.
- `ml.oracle.importer`: Logic for parsing Java code, Git history, and Maven dependencies.
- `ml.oracle.model`: Data structures and configuration.
- `ml.oracle.eval`: Execution-accuracy harness for the NL→Cypher pipeline.
