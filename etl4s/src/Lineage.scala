/*
 * +==========================================================================+
 * |                                 etl4s                                    |
 * |                     Powerful, whiteboard-style ETL                       |
 * |                 Compatible with Scala 2.12, 2.13, and 3                  |
 * |                                                                          |
 * | Copyright 2025 Matthieu Court (matthieu.court@protonmail.com)            |
 * | Apache License 2.0                                                       |
 * +==========================================================================+
 */

package etl4s

/**
 * Lineage metadata for pipeline tracking and visualization.
 *
 * Documents inputs, outputs, scheduling, and organization for data pipelines.
 * Supports automatic combination when nodes are composed.
 */
case class Lineage(
  name: String,
  inputs: List[String] = List.empty,
  outputs: List[String] = List.empty,
  upstreams: List[Any] = List.empty, // Node, Reader, or String
  schedule: String = "",
  cluster: String = "",
  description: String = "",
  group: String = "",
  tags: List[String] = List.empty,
  links: Map[String, String] = Map.empty
) {

  /**
   * Combines two lineages with union semantics for inputs/outputs.
   * Used for parallel and side-effect composition (&, &>, >>).
   */
  def combine(other: Lineage, separator: String = "_"): Lineage = Lineage(
    name = s"${this.name}${separator}${other.name}",
    inputs = (this.inputs ++ other.inputs).distinct,
    outputs = (this.outputs ++ other.outputs).distinct,
    upstreams = (this.upstreams ++ other.upstreams).distinct,
    schedule = if (this.schedule.nonEmpty) this.schedule else other.schedule,
    cluster = if (this.cluster.nonEmpty) this.cluster else other.cluster,
    description = if (this.description.nonEmpty) this.description else other.description,
    group = if (this.group.nonEmpty) this.group else other.group,
    tags = (this.tags ++ other.tags).distinct,
    links = this.links ++ other.links
  )

  /**
   * Chains two lineages with accumulating semantics.
   * Inputs and outputs are unioned (deduplicated).
   * Used for sequential composition (~>).
   */
  def chain(other: Lineage, separator: String = "_"): Lineage = Lineage(
    name = s"${this.name}${separator}${other.name}",
    inputs = (this.inputs ++ other.inputs).distinct,
    outputs = (this.outputs ++ other.outputs).distinct,
    upstreams = (this.upstreams ++ other.upstreams).distinct,
    schedule = if (this.schedule.nonEmpty) this.schedule else other.schedule,
    cluster = if (this.cluster.nonEmpty) this.cluster else other.cluster,
    description = if (this.description.nonEmpty) this.description else other.description,
    group = if (this.group.nonEmpty) this.group else other.group,
    tags = (this.tags ++ other.tags).distinct,
    links = this.links ++ other.links
  )
}

/**
 * Represents a pipeline node in the lineage graph.
 * Used for JSON serialization and visualization.
 */
case class LineageNode(
  name: String,
  input_sources: List[String],
  output_sources: List[String],
  upstream_pipelines: List[String],
  schedule: String,
  cluster: String,
  description: String = "",
  group: String = "",
  tags: List[String] = List.empty,
  links: Map[String, String] = Map.empty
)

/**
 * Represents a connection between pipeline components or data sources.
 * Used internally for graph traversal and visualization.
 */
case class LineageEdge(from: String, to: String, isDependency: Boolean = false)

/**
 * Represents a cluster in the lineage graph for visual organization.
 */
case class LineageCluster(
  name: String,
  description: String = "",
  parent: String = ""
)

/**
 * JSON representation of lineage information for serialization and visualization.
 * Conforms to the pipeviz JSON spec.
 */
case class LineageGraph(
  pipelines: List[LineageNode],
  datasources: List[String],
  clusters: List[LineageCluster] = List.empty,
  edges: List[LineageEdge] = List.empty // kept for internal graph ops
) {

  /**
   * Converts this lineage graph to JSON string.
   * Output conforms to the pipeviz JSON spec.
   */
  def toJson: String = {
    import LineageJsonHelpers._

    val pipelinesJson   = jsonArray(pipelines)(pipelineToJson)
    val datasourcesJson = jsonArray(datasources)(datasourceToJson)
    val clustersJson    = jsonArray(clusters)(clusterToJson)

    s"""{"pipelines":$pipelinesJson,"datasources":$datasourcesJson,"clusters":$clustersJson}"""
  }
}

private[etl4s] object LineageJsonHelpers {
  def jsonArray[A](items: Seq[A])(f: A => String): String = items.map(f).mkString("[", ",", "]")
  def quote(s: String): String                            = s""""$s""""
  def jsonField(key: String, value: String): String       = s""""$key":$value"""
  def jsonObject(fields: String*): String                 = fields.mkString("{", ",", "}")
  def jsonMap(m: Map[String, String]): String =
    m.map { case (k, v) => s""""$k":"$v"""" }.mkString("{", ",", "}")

  def pipelineToJson(p: LineageNode): String = {
    val requiredFields = List(
      jsonField("name", quote(p.name))
    )
    val optionalFields = List(
      if (p.description.nonEmpty) Some(jsonField("description", quote(p.description))) else None,
      if (p.input_sources.nonEmpty)
        Some(jsonField("input_sources", jsonArray(p.input_sources)(quote)))
      else None,
      if (p.output_sources.nonEmpty)
        Some(jsonField("output_sources", jsonArray(p.output_sources)(quote)))
      else None,
      if (p.upstream_pipelines.nonEmpty)
        Some(jsonField("upstream_pipelines", jsonArray(p.upstream_pipelines)(quote)))
      else None,
      if (p.cluster.nonEmpty) Some(jsonField("cluster", quote(p.cluster))) else None,
      if (p.group.nonEmpty) Some(jsonField("group", quote(p.group))) else None,
      if (p.schedule.nonEmpty) Some(jsonField("schedule", quote(p.schedule))) else None,
      if (p.tags.nonEmpty) Some(jsonField("tags", jsonArray(p.tags)(quote))) else None,
      if (p.links.nonEmpty) Some(jsonField("links", jsonMap(p.links))) else None
    ).flatten

    jsonObject((requiredFields ++ optionalFields): _*)
  }

  def datasourceToJson(name: String): String = s"""{"name":"$name"}"""

  def clusterToJson(c: LineageCluster): String = {
    val requiredFields = List(jsonField("name", quote(c.name)))
    val optionalFields = List(
      if (c.description.nonEmpty) Some(jsonField("description", quote(c.description))) else None,
      if (c.parent.nonEmpty) Some(jsonField("parent", quote(c.parent))) else None
    ).flatten
    jsonObject((requiredFields ++ optionalFields): _*)
  }
}
