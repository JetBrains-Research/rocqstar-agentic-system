[![JetBrains Research](https://jb.gg/badges/research.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

# RocqStar Agentic System

RocqStar is an **autonomous agentic system for Rocq (Coq)** — the first framework to explore applying multi-stage AI agents to interactive theorem proving (ITP).  
It builds on top of Rocq’s `coq-lsp` and extends it with a **Model Context Protocol (MCP)** layer, enabling AI agents to check, debug, and drive Rocq proofs programmatically.

The RocqStar Agentic System combines **planning**, **execution**, and **reflection** phases into a complete end-to-end proof generation loop.  
Incorporating multi-agent debates (MAD) significantly improves reasoning and robustness — an ablation study shows that planning and multi-agent reflection are key to its success.  
Our full agent solves **60% of the theorems** in the CoqPilot benchmark dataset.

All components — from the MCP interaction layer to the Kotlin-based agent implementation — are open-sourced in this repository.

📎 **Agent implementation:** [rocqstar-agent](./koogAgent)  
🧩 **MCP server:** [rocqstar-mcp-server](./mcpServer)  

## Components

- **[koogAgent](./koogAgent/README.md)**
  An AI agent written entirely in Kotlin using the innovative `Koog` framework by JetBrains.
  The agent communicates with the Rocq-MCP server, explores the context, and iteratively builds the proof.

- **[mcpServer](./mcpServer/README.md)**
  A lightweight pair of services that expose a **Model Context Protocol (MCP)** interface around Rocq’s `coq-lsp`,
  making it simple for autonomous or human-in-the-loop agents to _check_, _debug_, and _drive_ Rocq proofs programmatically.
