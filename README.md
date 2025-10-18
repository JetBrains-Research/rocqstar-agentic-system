# RocqStar Agentic System

All submodules contain their own README files with more detailed instructions.

## Components

- **[koogAgent](./koogAgent/README.md)**
  An AI agent written entirely in Kotlin using the innovative `Koog` framework by JetBrains.
  The agent communicates with the Rocq-MCP server, explores the context, and iteratively builds the proof.

- **[mcpServer](./mcpServer/README.md)**
  A lightweight pair of services that expose a **Model Context Protocol (MCP)** interface around Rocq’s `coq-lsp`,
  making it simple for autonomous or human-in-the-loop agents to _check_, _debug_, and _drive_ Rocq proofs programmatically.
