"""Read-only LangGraph calculation and explanation; transient state is never checkpointed."""

from typing import TypedDict

from langgraph.config import get_stream_writer
from langgraph.graph import END, START, StateGraph

from .adapters import OpenRouterExplanation
from .evidence import (
    AccountEvidence,
    AnalysisTask,
    calculate_evidence,
    render_facts,
)


class AnalysisState(TypedDict, total=False):
    task: AnalysisTask | None
    prompt: str
    account: AccountEvidence
    evidence: dict
    answer: str
    streaming: bool


def build_graph(provider: OpenRouterExplanation):
    def calculate(state: AnalysisState) -> dict:
        return {"evidence": calculate_evidence(state["account"])}

    async def explain(state: AnalysisState) -> dict:
        task = state["task"]
        prefix = ""
        if task is not None:
            facts = render_facts(task, state["evidence"])
            prefix = f"Account data — {state['evidence']['as_of']}\n{facts}\n\n"
        if state.get("streaming"):
            writer = get_stream_writer()
            if prefix:
                writer({"text": prefix})
            parts: list[str] = []
            async for part in provider.stream_explain(state["prompt"], state["evidence"]):
                parts.append(part)
                writer({"text": part})
            text = "".join(parts)
        else:
            text = await provider.explain(state["prompt"], state["evidence"])
        return {"answer": prefix + text}

    graph = StateGraph(AnalysisState)
    graph.add_node("calculate", calculate)
    graph.add_node("explain", explain)
    graph.add_edge(START, "calculate")
    graph.add_edge("calculate", "explain")
    graph.add_edge("explain", END)
    return graph.compile()  # No checkpoints, histories, credentials or database writes.
