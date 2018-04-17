package mist.api

import mist.api.args._
import mist.api.codecs.Encoder
import org.apache.spark.{SparkContext, SparkSessionUtils}
import org.apache.spark.api.java.JavaSparkContext
import org.apache.spark.sql.{SQLContext, SparkSession}
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.api.java.JavaStreamingContext
/**
  * Arguments for constructing contexts
  */
object ContextsArgs {

  val sparkContext: ArgDef[SparkContext] = SystemArg(
    Seq.empty,
    c => Extracted(c.sc)
  )

  val streamingContext: ArgDef[StreamingContext] = SystemArg(Seq(ArgInfo.StreamingContextTag),
    ctx => {
      val ssc = StreamingContext.getActiveOrCreate(() => new StreamingContext(ctx.sc, ctx.streamingDuration))
      Extracted(ssc)
    }
  )

  val sqlContext: ArgDef[SQLContext] = SystemArg(Seq(ArgInfo.SqlContextTag),
    ctx => sparkContext.map(SQLContext.getOrCreate).extract(ctx)
  )

  // HiveContext should be cached per jvm
  // see #325
  val hiveContext: ArgDef[HiveContext] = new SystemArg[HiveContext] {

    var cache: HiveContext = _

    override def extract(ctx: FnContext): ArgExtraction[HiveContext] = synchronized {
      ctx match {
        case c: FullFnContext =>
          if (cache == null)
            cache = new HiveContext(c.sc)
          Extracted(cache)
        case _ =>
          Missing(s"Unknown type of job context ${ctx.getClass.getSimpleName} expected ${FullFnContext.getClass.getSimpleName}")
      }
    }

    override def describe(): Seq[ArgInfo] = Seq(InternalArgument(
      Seq(ArgInfo.HiveContextTag, ArgInfo.SqlContextTag)))
  }

  val javaSparkContext: ArgDef[JavaSparkContext] = sparkContext.map(sc => new JavaSparkContext(sc))
  val javaStreamingContext: ArgDef[JavaStreamingContext] = SystemArg(Seq(ArgInfo.StreamingContextTag),
    ctx => streamingContext.map(scc => new JavaStreamingContext(scc)).extract(ctx))

  val sparkSession: ArgDef[SparkSession] = SystemArg(Seq(ArgInfo.SqlContextTag),
    ctx => sparkContext.map(sc => SparkSessionUtils.getOrCreate(sc, false)).extract(ctx)
  )

  val sparkSessionWithHive: ArgDef[SparkSession] = SystemArg(
    Seq(ArgInfo.SqlContextTag, ArgInfo.HiveContextTag),
    ctx => sparkContext.map(sc => SparkSessionUtils.getOrCreate(sc, true)).extract(ctx))
}

/**
  * Provide context combinators to complete job definition, that can take some
  * another arguments + spark computational context.
  * <p>
  * Available contexts:
  * <ul>
  *   <li>org.apache.spark.SparkContext</li>
  *   <li>org.apache.spark.streaming.StreamingContext</li>
  *   <li>org.apache.spark.sql.SQLContext</li>
  *   <li>org.apache.spark.sql.hive.HiveContext</li>
  * </ul>
  *</p>
  *
  * There are two ways how to define job using that standard combinators:
  * <p>
  *   For job which doesn't require any external argument except context
  *   use one of bellow functions:
  *   <ul>
  *     <li>onSparkContext((spark: SparkContext) => {...})</li>
  *     <li>onStreamingContext((ssc: StreamingContext) => {...})</li>
  *     <li>onSqlContext((sqlCtx: SQLContext) => {...})</li>
  *     <li>onHiveContext((hiveCtx: HiveContext) => {...})</li>
  *   </ul>
  *   In case when you have arguments you can call that functions on them
  *   {{{
  *     withArgs(arg[Int]("x") & arg[String]("str")).onSparkContext(
  *       (x: Int, str: String, sc: SparkContext) => {
  *         ...
  *     })
  *   }}}
  * </p>
  */
trait Contexts {

  import ContextsArgs._

  implicit class ContextsOps[A](args: ArgDef[A]) {

    /**
      * Define job execution function that takes current arguments and org.apache.spark.SparkContext
      */
    def onSparkContext[F, Cmb, Out](f: F)(
      implicit
      cmb: ArgCombiner.Aux[A, SparkContext, Cmb],
      fnT: FnForTuple.Aux[Cmb, F, Out],
      enc: Encoder[Out]
    ): Handle = Handle.fromLow(args.combine(sparkContext).apply(f), enc)

    /**
      * Define job execution function that takes current arguments and org.apache.spark.streaming.StreamingContext
      */
    def onStreamingContext[F, Cmb, Out](f: F)(
      implicit
      cmb: ArgCombiner.Aux[A, StreamingContext, Cmb],
      fnT: FnForTuple.Aux[Cmb, F, Out],
      enc: Encoder[Out]
    ): Handle = Handle.fromLow(args.combine(streamingContext).apply(f), enc)

    /**
      * Define job execution function that takes current arguments and org.apache.spark.sql.SQLContext
      */
    def onSqlContext[F, Cmb, Out](f: F)(
      implicit
      cmb: ArgCombiner.Aux[A, SQLContext, Cmb],
      fnT: FnForTuple.Aux[Cmb, F, Out],
      enc: Encoder[Out]
    ): Handle = Handle.fromLow(args.combine(sqlContext).apply(f), enc)

    /**
      * Define job execution function that takes current arguments and org.apache.spark.sql.hive.HiveContext
      */
    def onHiveContext[F, Cmb, Out](f: F)(
      implicit
      cmb: ArgCombiner.Aux[A, HiveContext, Cmb],
      fnT: FnForTuple.Aux[Cmb, F, Out],
      enc: Encoder[Out]
    ): Handle = Handle.fromLow(args.combine(hiveContext).apply(f), enc)

    def onSparkSession[F, Cmb, Out](f: F)(
      implicit
      cmb: ArgCombiner.Aux[A, SparkSession, Cmb],
      fnT: FnForTuple.Aux[Cmb, F, Out],
      enc: Encoder[Out]
    ): Handle = Handle.fromLow(args.combine(sparkSession).apply(f), enc)

    def onSparkSessionWithHive[F, Cmb, Out](f: F)(
      implicit
      cmb: ArgCombiner.Aux[A, SparkSession, Cmb],
      fnT: FnForTuple.Aux[Cmb, F, Out],
      enc: Encoder[Out]
    ): Handle = Handle.fromLow(args.combine(sparkSessionWithHive).apply(f), enc)
  }

  /**
    * Define job execution function that takes only org.apache.spark.SparkContext as an argument.
    */
  def onSparkContext[F, Out](f: F)(
    implicit
    fnT: FnForTuple.Aux[SparkContext, F, Out],
    enc: Encoder[Out]
  ): Handle = Handle.fromLow(sparkContext.apply(f), enc)

  /**
    * Define job execution function that takes only org.apache.spark.streaming.StreamingContext as an argument.
    */
  def onStreamingContext[F, Out](f: F)(
    implicit
    fnT: FnForTuple.Aux[StreamingContext, F, Out],
    enc: Encoder[Out]
  ): Handle = Handle.fromLow(streamingContext.apply(f), enc)

  /**
    * Define job execution function that takes only org.apache.spark.sql.SQLContext as an argument.
    */
  def onSqlContext[F, Out](f: F)(
    implicit
    fnT: FnForTuple.Aux[SQLContext, F, Out],
    enc: Encoder[Out]
  ): Handle = Handle.fromLow(sqlContext.apply(f), enc)

  /**
    * Define job execution function that takes only org.apache.spark.sql.hive.HiveContext as an argument.
    */
  def onHiveContext[F, Out](f: F)(
    implicit
    fnT: FnForTuple.Aux[HiveContext, F, Out],
    enc: Encoder[Out]
  ): Handle = Handle.fromLow(hiveContext.apply(f), enc)

  /**
    * Define job execution function that takes only org.apache.spark.sql.SparkSession as an argument.
    */
  def onSparkSession[F, Out](f: F)(
    implicit
    fnT: FnForTuple.Aux[SparkSession, F, Out],
    enc: Encoder[Out]
  ): Handle = Handle.fromLow(sparkSession.apply(f), enc)

  /**
    * Define job execution function that takes only org.apache.spark.sql.SparkSession
    * with enabled hive as an argument.
    */
  def onSparkSessionWithHive[F, Out](f: F)(
    implicit
    fnT: FnForTuple.Aux[SparkSession, F, Out],
    enc: Encoder[Out]
  ): Handle = Handle.fromLow(sparkSessionWithHive.apply(f), enc)
}

object Contexts extends Contexts
