import type { ProcessGraph } from '@/api/modules/process'
import type { RouteEdge, RouteNode } from '@/api/modules/masterData'

export function routeTopology(graph: ProcessGraph) {
  const nodes = [...graph.nodes].sort((a,b) => a.stepNo - b.stepNo || a.id - b.id)
  const byId = new Map(nodes.map(node => [node.id,node]))
  const previous = new Map<number, RouteEdge[]>(nodes.map(node => [node.id,[]]))
  const next = new Map<number, RouteEdge[]>(nodes.map(node => [node.id,[]]))
  for (const edge of graph.edges) {
    previous.get(edge.toNodeId)?.push(edge)
    next.get(edge.fromNodeId)?.push(edge)
  }
  const starts = nodes.filter(node => !previous.get(node.id)?.length)
  const slots = new Map<number, {stage:number; low:number; high:number; lane:number}>()
  const ready: RouteNode[] = [...starts]
  const occupied = new Set<string>()
  let stages = 1
  let diagram = graph.edges.length > 0
  for (let i = 0; i < ready.length; i++) {
    const node = ready[i]
    const parents = previous.get(node.id)!.map(edge => slots.get(edge.fromNodeId)!)
    const low = parents.length ? Math.min(...parents.map(p => p.low)) : i
    const high = parents.length ? Math.max(...parents.map(p => p.high)) : i
    const stage = parents.length ? 1 + Math.max(...parents.map(p => p.stage)) : 1
    const lane = low + high + 1
    const position = stage + '/' + lane
    if (occupied.has(position) || next.get(node.id)!.length > 1) diagram = false
    occupied.add(position)
    slots.set(node.id,{stage,low,high,lane})
    stages = Math.max(stages,stage)
    for (const edge of next.get(node.id)!) {
      const child = byId.get(edge.toNodeId)
      if (child && previous.get(child.id)!.every(e => slots.has(e.fromNodeId))) ready.push(child)
    }
  }
  // Conditional, branching and very long graphs retain each explicit edge in the dependency table.
  diagram &&= slots.size === nodes.length && stages <= 5 && graph.edges.every(e =>
    e.relationType === 'finish_to_start' && !e.label && !hasCondition(e))
  return {nodes,byId,previous,next,slots,stages,lanes:starts.length,diagram}
}
export function hasCondition(edge: RouteEdge) {
  return edge.condition != null && (typeof edge.condition !== 'object' || Object.keys(edge.condition).length > 0)
}
