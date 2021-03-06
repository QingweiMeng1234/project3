package project_3

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.graphx._
import org.apache.spark.storage.StorageLevel
import org.apache.log4j.{Level, Logger}

object main{
  val rootLogger = Logger.getRootLogger()
  rootLogger.setLevel(Level.ERROR)

  Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
  Logger.getLogger("org.spark-project").setLevel(Level.WARN)

  def LubyMIS(g_in: Graph[Int, Int]): Graph[Int, Int] = {
    var active_vs = 2L
    var counter = 0
    val r = scala.util.Random
    var g = g_in.mapVertices((id, i) => (0, 0F)) //start with all active vertices

    while (active_vs >= 1){ // remaining vertices
      counter += 1
      g = g.mapVertices((id, i) => (i._1, r.nextFloat)) //give active vertices random number
      val v_in = g.aggregateMessages[(Int, Float)]( //return 1 for all selected vertices
        d => { // Map Function
            // Send message to destination vertex containing counter and age
            d.sendToDst(if ((d.srcAttr._2 + d.srcAttr._1) > (d.dstAttr._2 + d.dstAttr._1)) (0, 0) else (1, 0));
            d.sendToSrc(if ((d.srcAttr._2 + d.srcAttr._1) > (d.dstAttr._2 + d.dstAttr._1)) (1, 0) else (0, 0))
          },
          (a,b) => ((math.min(a._1, b._1)), 0F)//take the minimum if two msg at one vertex
      )
      var g2 = Graph(v_in, g.edges)

      val v_deactivate = g2.aggregateMessages[(Int, Float)]( //return neighbors of selected
        d => {
          d.sendToDst(if (d.dstAttr._1 == 1) (1, 0) else (if (d.srcAttr._1 == 1) (-1, 0) else (0, 0)));
          d.sendToSrc(if (d.srcAttr._1 == 1) (1, 0) else (if (d.dstAttr._1 == 1) (-1, 0) else (0, 0)))
            },
            (a,b) => ((math.min(a._1, b._1)), 0) //can only receive 0 as message
      )

      g = Graph(v_deactivate, g.edges)
      g.cache()
      active_vs = g.vertices.filter({case (id, x) => (x._1 == 0)} ).count()
      println("***********************************************")
      println("Iteration# =" + counter + "remaining vertices = " + active_vs)
      println("***********************************************")
    }
    println("***********************************************")
    println("#Iteration = " + counter)
    println("***********************************************")
    return g.mapVertices((id, i) => i._1)
  }



  def verifyMIS(g_in: Graph[Int, Int]): Boolean = {
    //Msg: (source,dist)
    // Only works if it bidirected for every edge
    val rev = Graph(g_in.vertices,g_in.edges.reverse)
    //Create a bidirection graph
    val bi_g_in = Graph(g_in.vertices.union(rev.vertices),g_in.edges.union(rev.edges))
    val add_msg = bi_g_in.aggregateMessages[(Int,Int)](triplet=>{
        // Send source info to its neighbors
   if(triplet.srcAttr == 1 && triplet.dstAttr == 1)      triplet.sendToDst((1,1))
   else if(triplet.srcAttr == 1 && triplet.dstAttr == -1) triplet.sendToDst((1,-1))
   else if(triplet.srcAttr == -1 && triplet.dstAttr == 1)  triplet.sendToDst((-1,1))
   else triplet.sendToDst((-1,-1))},
     (v1,v2) => (math.max(v1._1,v2._1),math.max(v1._2,v2._2))
   )
    // the msg must be different to   make it MIS, so every entry must sum to 0, so no entry should be non-zero
   return add_msg.map(v => v._2._1+v._2._2).filter(s=>math.abs(s)>0).count()==0


  }

  def main(args: Array[String]) {
    val conf = new SparkConf().setAppName("project_3")
    val sc = new SparkContext(conf)
    val spark = SparkSession.builder.config(conf).getOrCreate()
  /* You can either use sc or spark */

    if(args.length == 0) {
      println("Usage: project_3 option = {compute, verify}")
      sys.exit(1)
    }
    if(args(0)=="compute") {
      if(args.length != 3) {
        println("Usage: project_3 compute graph_path output_path")
        sys.exit(1)
      }
      val startTimeMillis = System.currentTimeMillis()
      val edges = sc.textFile(args(1)).map(line => {val x = line.split(","); Edge(x(0).toLong, x(1).toLong , 1)} )
      val g = Graph.fromEdges[Int, Int](edges, 0, edgeStorageLevel = StorageLevel.MEMORY_AND_DISK, vertexStorageLevel = StorageLevel.MEMORY_AND_DISK)
      val g2 = LubyMIS(g)

      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000
      println("==================================")
      println("Luby's algorithm completed in " + durationSeconds + "s.")
      println("==================================")

      val g2df = spark.createDataFrame(g2.vertices)
      g2df.coalesce(1).write.format("csv").mode("overwrite").save(args(2))
    }
    else if(args(0)=="verify") {
      if(args.length != 3) {
        println("Usage: project_3 verify graph_path MIS_path")
        sys.exit(1)
      }

      val edges = sc.textFile(args(1)).map(line => {val x = line.split(","); Edge(x(0).toLong, x(1).toLong , 1)} )
      val vertices = sc.textFile(args(2)).map(line => {val x = line.split(","); (x(0).toLong, x(1).toInt) })
      val g = Graph[Int, Int](vertices, edges, edgeStorageLevel = StorageLevel.MEMORY_AND_DISK, vertexStorageLevel = StorageLevel.MEMORY_AND_DISK)

      val ans = verifyMIS(g)
      if(ans)
        println("Yes")
      else
        println("No")
    }
    else
    {
        println("Usage: project_3 option = {compute, verify}")
        sys.exit(1)
    }
  }
}
