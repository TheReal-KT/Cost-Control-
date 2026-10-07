"""Three read-only Python LangGraph nodes; state contains no caller text or credentials."""

from datetime import UTC, date, datetime
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

from .adapters import OpenRouterExplanation
from .evidence import (
    AnalysisTask,
    SubscriptionEvidence,
    calculate_evidence,
    render_facts,
    synthetic_records,
)


class AnalysisState(TypedDict, total=False):
    task: AnalysisTask
    today: date
    records: tuple[SubscriptionEvidence, ...]
    evidence: dict
    answer: str


def build_graph(provider: OpenRouterExplanation):
    def load_example(state: AnalysisState) -> dict:
        today = datetime.now(UTC).date()
        return {"today": today, "records": synthetic_records(today)}

    def calculate(state: AnalysisState) -> dict:
        return {"evidence": calculate_evidence(state["records"], state["today"])}

    async def explain(state: AnalysisState) -> dict:
        text = await provider.explain(state["task"], state["evidence"])
        facts = render_facts(state["task"], state["evidence"])
        return {"answer": f"Fictional example — {state['evidence']['as_of']}\n{facts}\n\n{text}"}

    graph = StateGraph(AnalysisState)
    graph.add_node("load_example", load_example)
    graph.add_node("calculate", calculate)
    graph.add_node("explain", explain)
    graph.add_edge(START, "load_example")
    graph.add_edge("load_example", "calculate")
    graph.add_edge("calculate", "explain")
    graph.add_edge("explain", END)
    return graph.compile()  # No checkpoints, histories, credentials or database writes.
