package slick.jdbc

import java.sql.{PreparedStatement, ResultSet, Statement}

import scala.collection.mutable.Builder
import scala.language.existentials
import scala.util.control.NonFatal
import slick.SlickException
import slick.ast.*
import slick.ast.ColumnOption.PrimaryKey
import slick.ast.TypeUtil.:@
import slick.ast.Util.*
import slick.dbio.*
import slick.lifted.{CompiledStreamingExecutable, FlatShapeLevel, Query, Shape}
import slick.relational.{CompiledMapping, ResultConverter}
import slick.sql.{FixedSqlAction, FixedSqlStreamingAction, SqlActionComponent}
import slick.util.{ignoreFollowOnError, DumpInfo, SQLBuilder}
import slick.compat.collection.*


trait JdbcActionComponent extends SqlActionComponent { self: JdbcProfile =>

  type ProfileAction[+R, +S <: NoStream, -E <: Effect] = FixedSqlAction[R, S, E]
  type StreamingProfileAction[+R, +T, -E <: Effect] = FixedSqlStreamingAction[R, T, E]

  type RowsPerStatement >: slick.jdbc.RowsPerStatement.One.type <: slick.jdbc.RowsPerStatement
  def defaultRowsPerStatement: RowsPerStatement

  abstract class SimpleJdbcProfileAction[+R](_name: String, val statements: Vector[String]) extends SynchronousDatabaseAction[R, NoStream, JdbcBackend#JdbcActionContext, JdbcBackend#JdbcStreamingActionContext, Effect] with ProfileAction[R, NoStream, Effect] { self =>
    def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): R
    final override def getDumpInfo = super.getDumpInfo.copy(name = _name)
    final def run(ctx: JdbcBackend#JdbcActionContext): R = run(ctx, statements)
    final def overrideStatements(_statements: Iterable[String]): ProfileAction[R, NoStream, Effect] = new SimpleJdbcProfileAction[R](_name, _statements.toVector) {
      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): R = self.run(ctx, statements)
    }
  }

  protected object StartTransaction extends SynchronousDatabaseAction[Unit, NoStream, JdbcBackend#JdbcActionContext, JdbcBackend#JdbcStreamingActionContext, Effect] {
    def run(ctx: JdbcBackend#JdbcActionContext): Unit = {
      ctx.pin
      ctx.session.startInTransaction
    }
    def getDumpInfo = DumpInfo(name = "StartTransaction")
  }

  protected object Commit extends SynchronousDatabaseAction[Unit, NoStream, JdbcBackend#JdbcActionContext, JdbcBackend#JdbcStreamingActionContext, Effect] {
    def run(ctx: JdbcBackend#JdbcActionContext): Unit =
      try ctx.session.endInTransaction(ctx.session.conn.commit()) finally ctx.unpin
    def getDumpInfo = DumpInfo(name = "Commit")
  }

  protected object Rollback extends SynchronousDatabaseAction[Unit, NoStream, JdbcBackend#JdbcActionContext, JdbcBackend#JdbcStreamingActionContext, Effect] {
    def run(ctx: JdbcBackend#JdbcActionContext): Unit =
      try ctx.session.endInTransaction(ctx.session.conn.rollback()) finally ctx.unpin
    def getDumpInfo = DumpInfo(name = "Rollback")
  }

  protected class PushStatementParameters(p: JdbcBackend.StatementParameters) extends SynchronousDatabaseAction[Unit, NoStream, JdbcBackend#JdbcActionContext, JdbcBackend#JdbcStreamingActionContext, Effect] {
    def run(ctx: JdbcBackend#JdbcActionContext): Unit = ctx.pushStatementParameters(p)
    def getDumpInfo = DumpInfo(name = "PushStatementParameters", mainInfo = p.toString)
  }

  protected object PopStatementParameters extends SynchronousDatabaseAction[Unit, NoStream, JdbcBackend#JdbcActionContext, JdbcBackend#JdbcStreamingActionContext, Effect] {
    def run(ctx: JdbcBackend#JdbcActionContext): Unit = ctx.popStatementParameters
    def getDumpInfo = DumpInfo(name = "PopStatementParameters")
  }

  protected class SetTransactionIsolation(ti: Int) extends SynchronousDatabaseAction[Int, NoStream, JdbcBackend#JdbcActionContext, JdbcBackend#JdbcStreamingActionContext, Effect] {
    def run(ctx: JdbcBackend#JdbcActionContext): Int = {
      val c = ctx.session.conn
      val old = c.getTransactionIsolation
      c.setTransactionIsolation(ti)
      old
    }
    def getDumpInfo = DumpInfo(name = "SetTransactionIsolation")
  }

  class JdbcActionExtensionMethods[E <: Effect, R, S <: NoStream](a: DBIOAction[R, S, E]) {

    /** Run this Action transactionally. This does not guarantee failures to be atomic in the
      * presence of error handling combinators. If multiple `transactionally` combinators are
      * nested, only the outermost one will be backed by an actual database transaction. Depending
      * on the outcome of running the Action it surrounds, the transaction is committed if the
      * wrapped Action succeeds, or rolled back if the wrapped Action fails. When called on a
      * [[slick.dbio.SynchronousDatabaseAction]], this combinator gets fused into the
      * action. */
    def transactionally: DBIOAction[R, S, E with Effect.Transactional] = SynchronousDatabaseAction.fuseUnsafe(
      StartTransaction.andThen(a).cleanUp(eo => if(eo.isEmpty) Commit else Rollback)(DBIO.sameThreadExecutionContext)
        .asInstanceOf[DBIOAction[R, S, E with Effect.Transactional]]
    )

    /** Run this Action with the specified transaction isolation level. This should be used around
      * the outermost `transactionally` Action. The semantics of using it inside a transaction are
      * database-dependent. It does not create a transaction by itself but it pins the session. */
    def withTransactionIsolation(ti: TransactionIsolation): DBIOAction[R, S, E] = {
      val isolated =
        (new SetTransactionIsolation(ti.intValue)).flatMap(old => a.andFinally(new SetTransactionIsolation(old)))(DBIO.sameThreadExecutionContext)
      val fused =
        if(a.isInstanceOf[SynchronousDatabaseAction[?, ?, ?, ?, ?]]) SynchronousDatabaseAction.fuseUnsafe(isolated)
        else isolated
      fused.withPinnedSession
    }

    /** Run this Action with the given statement parameters. Any unset parameter will use the
      * current value. The following parameters can be set:
      *
      * @param rsType The JDBC `ResultSetType`
      * @param rsConcurrency The JDBC `ResultSetConcurrency`
      * @param rsHoldability The JDBC `ResultSetHoldability`
      * @param statementInit A function which is run on every `Statement` or `PreparedStatement`
      *                      directly after creating it. This can be used to set additional
      *                      statement parameters (e.g. `setQueryTimeout`). When multiple
      *                      `withStatementParameters` Actions are nested, all init functions
      *                      are run, starting with the outermost one.
      * @param fetchSize The fetch size for all statements or 0 for the default. */
    def withStatementParameters(rsType: ResultSetType = null,
                                rsConcurrency: ResultSetConcurrency = null,
                                rsHoldability: ResultSetHoldability = null,
                                statementInit: Statement => Unit = null,
                                fetchSize: Int = 0): DBIOAction[R, S, E] =
      (new PushStatementParameters(JdbcBackend.StatementParameters(rsType, rsConcurrency, rsHoldability, statementInit, fetchSize))).
        andThen(a).andFinally(PopStatementParameters)
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////// Query Actions
  ///////////////////////////////////////////////////////////////////////////////////////////////

  type QueryActionExtensionMethods[R, S <: NoStream] = JdbcQueryActionExtensionMethodsImpl[R, S]
  type StreamingQueryActionExtensionMethods[R, T] = JdbcStreamingQueryActionExtensionMethodsImpl[R, T]

  def createQueryActionExtensionMethods[R, S <: NoStream](tree: Node, param: Any): QueryActionExtensionMethods[R, S] =
    new QueryActionExtensionMethods[R, S](tree, param)
  def createStreamingQueryActionExtensionMethods[R, T](tree: Node, param: Any): StreamingQueryActionExtensionMethods[R, T] =
    new StreamingQueryActionExtensionMethods[R, T](tree, param)

  class MutatingResultAction[T](rsm: ResultSetMapping, elemType: Type, collectionType: CollectionType, sql: String, param: Any, sendEndMarker: Boolean) extends SynchronousDatabaseAction[Nothing, Streaming[ResultSetMutator[T]], JdbcBackend#JdbcActionContext, JdbcBackend#JdbcStreamingActionContext, Effect] with ProfileAction[Nothing, Streaming[ResultSetMutator[T]], Effect] { streamingAction =>
    class Mutator(val prit: PositionedResultIterator[T], val bufferNext: Boolean, val inv: QueryInvokerImpl[T]) extends ResultSetMutator[T] {
      val pr = prit.pr
      val rs = pr.rs
      var current: T = _
      /** The state of the stream. 0 = in result set, 1 = before end marker, 2 = after end marker. */
      var state = 0
      def row = if(state > 0) throw new SlickException("After end of result set") else current
      def row_=(value: T): Unit = {
        if(state > 0) throw new SlickException("After end of result set")
        pr.restart
        inv.updateRowValues(pr, value)
        rs.updateRow()
      }
      def += (value: T): Unit = {
        rs.moveToInsertRow()
        pr.restart
        inv.updateRowValues(pr, value)
        rs.insertRow()
        if(state == 0) rs.moveToCurrentRow()
      }
      def delete: Unit = {
        if(state > 0) throw new SlickException("After end of result set")
        rs.deleteRow()
        if(invokerPreviousAfterDelete) rs.previous()
      }
      def emitStream(ctx: JdbcBackend#JdbcStreamingActionContext, limit: Long): this.type = {
        var count = 0L
        try {
          while(count < limit && state == 0) {
            if(!pr.nextRow) state = if(sendEndMarker) 1 else 2
            if(state == 0) {
              current = inv.extractValue(pr)
              count += 1
              ctx.emit(this)
            }
          }
          if(count < limit && state == 1) {
            ctx.emit(this)
            state = 2
          }
        } catch {
          case NonFatal(ex) =>
            try prit.close() catch ignoreFollowOnError
            throw ex
        }
        if(state < 2) this else null.asInstanceOf[this.type]
      }
      def end = if(state > 1) throw new SlickException("After end of result set") else state > 0
      override def toString = s"Mutator(state = $state, current = $current)"
    }
    type StreamState = Mutator
    override def statements: List[String] = List(sql)
    def run(ctx: JdbcBackend#JdbcActionContext) =
      throw new SlickException("The result of .mutate can only be used in a streaming way")
    override def emitStream(ctx: JdbcBackend#JdbcStreamingActionContext, limit: Long, state: StreamState): StreamState = {
      val mu = if(state ne null) state else {
        val inv = createQueryInvoker[T](rsm, param, sql)
        new Mutator(
          inv.results(0, defaultConcurrency = invokerMutateConcurrency, defaultType = invokerMutateType)(ctx.session).getOrElse(throw new NoSuchElementException),
          ctx.bufferNext,
          inv)
      }
      mu.emitStream(ctx, limit)
    }
    override def cancelStream(ctx: JdbcBackend#JdbcStreamingActionContext, state: StreamState): Unit = state.prit.close()
    override def getDumpInfo = super.getDumpInfo.copy(name = "mutate")
    def overrideStatements(_statements: Iterable[String]): MutatingResultAction[T] =
      new MutatingResultAction[T](rsm, elemType, collectionType, _statements.head, param, sendEndMarker)
  }

  class JdbcQueryActionExtensionMethodsImpl[R, S <: NoStream](tree: Node, param: Any)
    extends BasicQueryActionExtensionMethodsImpl[R, S] {

    def result: ProfileAction[R, S, Effect.Read] = {
      def findSql(n: Node): String = n match {
        case c: CompiledStatement => c.extra.asInstanceOf[SQLBuilder.Result].sql
        case ParameterSwitch(cases, default) =>
          findSql(cases.find { case (f, n) => f(param) }.map(_._2).getOrElse(default))
      }
      (tree match {
        case (rsm @ ResultSetMapping(_, compiled, CompiledMapping(_, elemType))) :@ (ct: CollectionType) =>
          val sql = findSql(compiled)
          new StreamingInvokerAction[R, Any, Effect] { streamingAction =>
            protected[this] def createInvoker(sql: Iterable[String]): Invoker[Any] = createQueryInvoker(rsm, param, sql.head)
            protected[this] def createBuilder = ct.cons.createBuilder(ct.elementType.classTag).asInstanceOf[Builder[Any, R]]
            def statements: Iterable[String] = List(sql)
            override def getDumpInfo = super.getDumpInfo.copy(name = "result")
          }
        case First(rsm @ ResultSetMapping(_, compiled, _)) =>
          val sql = findSql(compiled)
          new SimpleJdbcProfileAction[R]("result", Vector(sql)) {
            def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): R =
              createQueryInvoker[R](rsm, param, sql.head).first(ctx.session)
          }
      }).asInstanceOf[ProfileAction[R, S, Effect.Read]]
    }
  }

  class JdbcStreamingQueryActionExtensionMethodsImpl[R, T](tree: Node, param: Any)
    extends JdbcQueryActionExtensionMethodsImpl[R, Streaming[T]](tree, param)
      with BasicStreamingQueryActionExtensionMethodsImpl[R, T] {

    override def result: StreamingProfileAction[R, T, Effect.Read] =
      super.result.asInstanceOf[StreamingProfileAction[R, T, Effect.Read]]

    /** Same as `mutate(sendEndMarker = false)`. */
    def mutate: ProfileAction[Nothing, Streaming[ResultSetMutator[T]], Effect.Read with Effect.Write] = mutate(false)

    /** Create an Action that can be streamed in order to modify a mutable result set. All stream
      * elements will be the same [[slick.jdbc.ResultSetMutator]] object but it is in a different state each
      * time. Thre resulting stream is always non-buffered and events can be processed either
      * synchronously or asynchronously (but all processing must happen in sequence).
      *
      * @param sendEndMarker If set to true, an extra event is sent after the end of the result
      *                      set, poviding you with a chance to insert additional rows after
      *                      seeing all results. Only `end` (to check for this special event) and
      *                      `insert` may be called in the ResultSetMutator in this case. */
    def mutate(sendEndMarker: Boolean = false): ProfileAction[Nothing, Streaming[ResultSetMutator[T]], Effect.Read with Effect.Write] = {
      val sql = tree.findNode(_.isInstanceOf[CompiledStatement]).get
        .asInstanceOf[CompiledStatement].extra.asInstanceOf[SQLBuilder.Result].sql
      val (rsm @ ResultSetMapping(_, _, CompiledMapping(_, elemType))) :@ (ct: CollectionType) = tree: @unchecked
      new MutatingResultAction[T](rsm, elemType, ct, sql, param, sendEndMarker)
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////// Delete Actions
  ///////////////////////////////////////////////////////////////////////////////////////////////

  type DeleteActionExtensionMethods = DeleteActionExtensionMethodsImpl

  def createDeleteActionExtensionMethods(tree: Node, param: Any): DeleteActionExtensionMethods =
    new DeleteActionExtensionMethods(tree, param)

  class DeleteActionExtensionMethodsImpl(tree: Node, param: Any) {
    /** An Action that deletes the data selected by this query. */
    def delete: ProfileAction[Int, NoStream, Effect.Write] = {
      val ResultSetMapping(_, CompiledStatement(_, sres: SQLBuilder.Result, _), _) = tree: @unchecked
      new SimpleJdbcProfileAction[Int]("delete", Vector(sres.sql)) {
        def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): Int = ctx.session.withPreparedStatement(sql.head) { st =>
          sres.setter(st, 1, param)
          st.executeUpdate
        }
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////// Schema Actions
  ///////////////////////////////////////////////////////////////////////////////////////////////

  type SchemaActionExtensionMethods = JdbcSchemaActionExtensionMethodsImpl

  def createSchemaActionExtensionMethods(schema: SchemaDescription): SchemaActionExtensionMethods =
    new JdbcSchemaActionExtensionMethodsImpl(schema)

  class JdbcSchemaActionExtensionMethodsImpl(schema: SchemaDescription)
    extends RelationalSchemaActionExtensionMethodsImpl {

    def create: ProfileAction[Unit, NoStream, Effect.Schema] = new SimpleJdbcProfileAction[Unit]("schema.create", schema.createStatements.toVector) {
      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): Unit =
        for(s <- sql) ctx.session.withPreparedStatement(s)(_.execute)
    }

    def createIfNotExists: ProfileAction[Unit, NoStream, Effect.Schema] = new SimpleJdbcProfileAction[Unit]("schema.createIfNotExists", schema.createIfNotExistsStatements.toVector) {
      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): Unit =
        for(s <- sql) ctx.session.withPreparedStatement(s)(_.execute)
    }

    def truncate: ProfileAction[Unit, NoStream, Effect.Schema] = new SimpleJdbcProfileAction[Unit]("schema.truncate" , schema.truncateStatements.toVector ){
      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): Unit =
        for(s <- sql) ctx.session.withPreparedStatement(s)(_.execute)
    }

    def drop: ProfileAction[Unit, NoStream, Effect.Schema] = new SimpleJdbcProfileAction[Unit]("schema.drop", schema.dropStatements.toVector) {
      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): Unit =
        for(s <- sql) ctx.session.withPreparedStatement(s)(_.execute)
    }

    def dropIfExists: ProfileAction[Unit, NoStream, Effect.Schema] = new SimpleJdbcProfileAction[Unit]("schema.dropIfExists", schema.dropIfExistsStatements.toVector) {
      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): Unit =
        for(s <- sql) ctx.session.withPreparedStatement(s)(_.execute)
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////// Update Actions
  ///////////////////////////////////////////////////////////////////////////////////////////////

  type UpdateActionExtensionMethods[T] = UpdateActionExtensionMethodsImpl[T]

  def createUpdateActionExtensionMethods[T](tree: Node, param: Any): UpdateActionExtensionMethods[T] =
    new UpdateActionExtensionMethodsImpl[T](tree, param)

  class UpdateActionExtensionMethodsImpl[T](tree: Node, param: Any) {
    protected[this] val ResultSetMapping(_,
      CompiledStatement(_, sres: SQLBuilder.Result, _),
      CompiledMapping(_converter, _)) = tree
    protected[this] val converter = _converter.asInstanceOf[ResultConverter[ResultSet, PreparedStatement, ResultSet, T]]

    /** An Action that updates the data selected by this query. */
    def update(value: T): ProfileAction[Int, NoStream, Effect.Write] = {
      new SimpleJdbcProfileAction[Int]("update", Vector(sres.sql)) {
        def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): Int = ctx.session.withPreparedStatement(sql.head) { st =>
          st.clearParameters
          converter.set(value, st, 0)
          sres.setter(st, converter.width+1, param)
          st.executeUpdate
        }
      }
    }
    /** Get the statement used by `update` */
    def updateStatement: String = sres.sql
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////// Insert Actions
  ///////////////////////////////////////////////////////////////////////////////////////////////

  type InsertActionExtensionMethods[T] = CountingInsertActionComposer[T]

  def createInsertActionExtensionMethods[T](compiled: CompiledInsert): InsertActionExtensionMethods[T] =
    new CountingInsertActionComposerImpl[T](compiled)
  def createReturningInsertActionComposer[U, QR, RU](compiled: CompiledInsert, keys: Node, mux: (U, QR) => RU): ReturningInsertActionComposer[U, RU] =
    new ReturningInsertActionComposerImpl[U, QR, RU](compiled, keys, mux)

  protected lazy val useServerSideUpsert = capabilities contains JdbcCapabilities.insertOrUpdate
  protected lazy val useTransactionForUpsert = !useServerSideUpsert
  protected lazy val useServerSideUpsertReturning = useServerSideUpsert
  protected lazy val useTransactionForUpsertReturning = !useServerSideUpsertReturning

  //////////////////////////////////////////////////////////// InsertActionComposer Traits

  /** Extension methods to generate the JDBC-specific insert actions. */
  trait SimpleInsertActionComposer[U] extends super.InsertActionExtensionMethodsImpl[U] {
    /** The return type for `insertOrUpdate` operations. Note that the Option return value will be None if it was an update and Some if it was an insert*/
    type SingleInsertOrUpdateResult

    /** Get the SQL statement for a standard (soft) insert */
    def insertStatement: String

    /** Get the SQL statement for a forced insert */
    def forceInsertStatement: String

    /** Insert a single row, skipping AutoInc columns. */
    def += (value: U): ProfileAction[SingleInsertResult, NoStream, Effect.Write]

    /** Insert a single row, including AutoInc columns. This is not supported
      * by all database engines (see
      * [[slick.jdbc.JdbcCapabilities.forceInsert]]). */
    def forceInsert(value: U): ProfileAction[SingleInsertResult, NoStream, Effect.Write]

    /** Insert multiple rows, skipping AutoInc columns.
      *
      * @param values           the rows to insert
      * @param rowsPerStatement [[RowsPerStatement.All]] to use a single statement to insert all rows at once,
      *                         or [[RowsPerStatement.One]] to use a separate SQL statement for each row.
      *                         Even so, if supported this will use JDBC's batching functionality.
      * @return Some(rowsAffected), or None if the database returned no row
      *         count for some part of the batch. If any part of the batch fails, an
      *         exception is thrown.
      */
    def insertAll(values: Iterable[U], rowsPerStatement: RowsPerStatement = defaultRowsPerStatement): ProfileAction[
      MultiInsertResult,
      NoStream,
      Effect.Write
    ]

    /** Insert multiple rows, skipping AutoInc columns.
      * Uses JDBC's batch update feature if supported by the JDBC driver.
      * Returns Some(rowsAffected), or None if the database returned no row
      * count for some part of the batch. If any part of the batch fails, an
      * exception is thrown.
      *
      * This method is a shorthand for [[insertAll]] with [[RowsPerStatement.One]].
      */
    final def ++= (values: Iterable[U]): ProfileAction[MultiInsertResult, NoStream, Effect.Write] =
      insertAll(values, RowsPerStatement.One)

    /** Insert multiple rows, including AutoInc columns.
      * This is not supported by all database engines (see
      * [[slick.jdbc.JdbcCapabilities.forceInsert]]).
      * Uses JDBC's batch update feature if supported by the JDBC driver.
      * Returns Some(rowsAffected), or None if the database returned no row
      * count for some part of the batch. If any part of the batch fails, an
      * exception is thrown. */
    def forceInsertAll(values: Iterable[U]): ProfileAction[MultiInsertResult, NoStream, Effect.Write]

    /** Insert a single row if its primary key does not exist in the table,
      * otherwise update the existing record.
      * Note that the return value will be None if an update was performed and Some if the operation was insert
      */
    def insertOrUpdate(value: U): ProfileAction[SingleInsertOrUpdateResult, NoStream, Effect.Write]

    /** Insert multiple rows if its primary key does not exist in the table,
     * otherwise update the existing record.
     * Returns Some(rowsAffected), or None if the database returned no row
     * count for some part of the batch. If any part of the batch fails, an
     * exception is thrown.
     * The option parameter specifies how the operation is to be performed.(default is [[RowsPerStatement.All]])
     * Note unlike [[insertOrUpdate]], client-side emulation is not supported. */
    def insertOrUpdateAll(values: Iterable[U], option: RowsPerStatement = defaultRowsPerStatement)
    : ProfileAction[MultiInsertResult, NoStream, Effect.Write]
  }

  /** Extension methods to generate the JDBC-specific insert actions. */
  trait InsertActionComposer[U] extends SimpleInsertActionComposer[U] {
    /** The result type of operations that insert data produced by another query */
    type QueryInsertResult

    /** Get the SQL statement for inserting a single row from a scalar expression */
    def forceInsertStatementFor[TT](c: TT)(implicit shape: Shape[? <: FlatShapeLevel, TT, U, ?]): String

    /** Get the SQL statement for inserting data produced by another query */
    def forceInsertStatementFor[TT, C[_]](query: Query[TT, U, C]): String

    /** Get the SQL statement for inserting data produced by another query */
    def forceInsertStatementFor[TT, C[_]](compiledQuery: CompiledStreamingExecutable[Query[TT, U, C], ?, ?]): String

    /** Insert a single row from a scalar expression */
    def forceInsertExpr[TT](c: TT)(implicit shape: Shape[? <: FlatShapeLevel, TT, U, ?]): ProfileAction[QueryInsertResult, NoStream, Effect.Write]

    /** Insert data produced by another query */
    def forceInsertQuery[TT, C[_]](query: Query[TT, U, C]): ProfileAction[QueryInsertResult, NoStream, Effect.Write]

    /** Insert data produced by another query */
    def forceInsertQuery[TT, C[_]](compiledQuery: CompiledStreamingExecutable[Query[TT, U, C], ?, ?]): ProfileAction[QueryInsertResult, NoStream, Effect.Write]
  }

  /** An InsertInvoker that returns the number of affected rows. */
  trait CountingInsertActionComposer[U] extends InsertActionComposer[U] {
    type SingleInsertResult = Int
    type MultiInsertResult = Option[Int]
    type SingleInsertOrUpdateResult = Int
    type QueryInsertResult = Int

    /** Add a mapping from the inserted values and the generated key to compute a new return value.
     * When using [[insertAll]], some JDBC drivers may not be able to return the generated key.
     * In that case, the collection of keys returned by [[insertAll]] will be Nil. */
    def returning[RT, RU, C[_]](value: Query[RT, RU, C]): ReturningInsertActionComposer[U, RU]
  }

  /** An InsertActionComposer that returns generated keys or other columns. */
  trait ReturningInsertActionComposer[U, RU] extends InsertActionComposer[U] with IntoInsertActionComposer[U, RU] { self =>
    /** Specifies a mapping from inserted values and generated keys to a desired value.
      * @param f Function that maps inserted values and generated keys to a desired value.
      * @tparam R target type of the mapping */
    def into[R](f: (U, RU) => R): IntoInsertActionComposer[U, R]
  }

  /** An InsertActionComposer that returns a mapping of the inserted and generated data. */
  trait IntoInsertActionComposer[U, RU] extends SimpleInsertActionComposer[U] { self =>
    type SingleInsertResult = RU
    type MultiInsertResult = Seq[RU]
    type SingleInsertOrUpdateResult = Option[RU]
    type QueryInsertResult = Seq[RU]
  }

  //////////////////////////////////////////////////////////// InsertActionComposer Implementations

  protected abstract class InsertActionComposerImpl[U](val compiled: CompiledInsert) extends InsertActionComposer[U] {
    protected[this] def buildQueryBasedInsert[TT, C[_]](query: Query[TT, U, C]): SQLBuilder.Result =
      compiled.forceInsert.ibr.buildInsert(queryCompiler.run(query.toNode).tree)

    protected[this] def buildQueryBasedInsert[TT, C[_]](compiledQuery: CompiledStreamingExecutable[Query[TT, U, C], ?, ?]): SQLBuilder.Result =
      compiled.forceInsert.ibr.buildInsert(compiledQuery.compiledQuery)

    def insertStatement = compiled.standardInsert.sql

    def forceInsertStatement = compiled.forceInsert.sql

    def += (value: U): ProfileAction[SingleInsertResult, NoStream, Effect.Write] =
      new SingleInsertAction(compiled.standardInsert, value)

    def forceInsert(value: U): ProfileAction[SingleInsertResult, NoStream, Effect.Write] =
      new SingleInsertAction(compiled.forceInsert, value)

    def insertAll(values: Iterable[U], rowsPerStatement: RowsPerStatement): ProfileAction[
      MultiInsertResult,
      NoStream,
      Effect.Write
    ] =
      new MultiInsertAction(compiled.standardInsert, values, rowsPerStatement)

    def forceInsertAll(values: Iterable[U]): ProfileAction[MultiInsertResult, NoStream, Effect.Write] =
      new MultiInsertAction(compiled.forceInsert, values, RowsPerStatement.One)

    def insertOrUpdate(value: U): ProfileAction[SingleInsertOrUpdateResult, NoStream, Effect.Write] =
      new InsertOrUpdateAction(value)

    override def insertOrUpdateAll(values: Iterable[U], rowsPerStatement: RowsPerStatement): ProfileAction[
      MultiInsertResult,
      NoStream,
      Effect.Write
    ] =
      if (!capabilities.contains(JdbcCapabilities.insertOrUpdate))
        throw new SlickException("insertOrUpdateAll is not supported for this profile")
      else
        new InsertOrUpdateAllAction(values, rowsPerStatement)

    def forceInsertStatementFor[TT](c: TT)(implicit shape: Shape[? <: FlatShapeLevel, TT, U, ?]) =
      buildQueryBasedInsert(Query(c)(shape)).sql

    def forceInsertStatementFor[TT, C[_]](query: Query[TT, U, C]) =
      buildQueryBasedInsert(query).sql

    def forceInsertStatementFor[TT, C[_]](compiledQuery: CompiledStreamingExecutable[Query[TT, U, C], ?, ?]) =
      buildQueryBasedInsert(compiledQuery).sql

    def forceInsertExpr[TT](c: TT)(implicit shape: Shape[? <: FlatShapeLevel, TT, U, ?]): ProfileAction[QueryInsertResult, NoStream, Effect.Write] =
      new InsertQueryAction(buildQueryBasedInsert((Query(c)(shape))), null)

    def forceInsertQuery[TT, C[_]](query: Query[TT, U, C]): ProfileAction[QueryInsertResult, NoStream, Effect.Write] =
      new InsertQueryAction(buildQueryBasedInsert(query), null)

    def forceInsertQuery[TT, C[_]](compiledQuery: CompiledStreamingExecutable[Query[TT, U, C], ?, ?]): ProfileAction[QueryInsertResult, NoStream, Effect.Write] =
      new InsertQueryAction(buildQueryBasedInsert(compiledQuery), compiledQuery.param)

    protected def useServerSideUpsert = self.useServerSideUpsert
    protected def useTransactionForUpsert = self.useTransactionForUpsert
    protected def useBatchUpdates(implicit session: JdbcBackend#Session) = session.capabilities.supportsBatchUpdates

    protected def retOne(st: Statement, value: U, updateCount: Int): SingleInsertResult
    protected def retMany(values: Iterable[U], individual: Seq[SingleInsertResult]): MultiInsertResult
    protected def retManyMultiRowStatement(st: Statement, values: Iterable[U], updateCount: Int): MultiInsertResult
    protected def retManyBatch(st: Statement, values: Iterable[U], updateCounts: Array[Int]): MultiInsertResult
    protected def retOneInsertOrUpdate(st: Statement, value: U, updateCount: Int): SingleInsertOrUpdateResult
    protected def retOneInsertOrUpdateFromInsert(st: Statement, value: U, updateCount: Int): SingleInsertOrUpdateResult
    protected def retOneInsertOrUpdateFromUpdate: SingleInsertOrUpdateResult
    protected def retQuery(st: Statement, updateCount: Int): QueryInsertResult

    protected def preparedInsert[T](sql: String, session: JdbcBackend#Session)(f: PreparedStatement => T) =
      session.withPreparedStatement(sql)(f)

    protected def preparedOther[T](sql: String, session: JdbcBackend#Session)(f: PreparedStatement => T) =
      session.withPreparedStatement(sql)(f)

    private def insertSingleRow(sql: Vector[String], ctx: JdbcBackend#JdbcActionContext, a: compiled.Artifacts, value: U) =
      preparedInsert(sql.head, ctx.session) { st =>
        st.clearParameters()
        a.converter.set(value, st, 0)
        val count = st.executeUpdate()
        retOne(st, value, count)
      }

    final class SingleInsertAction(a: compiled.Artifacts, value: U)
      extends SimpleJdbcProfileAction[SingleInsertResult]("SingleInsertAction", Vector(a.sql)) {
      override def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]) =
        insertSingleRow(sql, ctx, a, value)
    }

    class MultiInsertAction(a: compiled.Artifacts, values: Iterable[U], rowsPerStatement: slick.jdbc.RowsPerStatement)
      extends SimpleJdbcProfileAction[MultiInsertResult](
        _name = "MultiInsertAction",
        statements = rowsPerStatement match {
          case RowsPerStatement.One => Vector(a.sql)
          case RowsPerStatement.All => Vector(a.ibr.buildMultiRowInsert(values.size))
        }
      ) {
      protected def doUnbatched(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]) = {
        val results =
          for (value <- values.iterator) yield
            insertSingleRow(sql, ctx, a, value)

        retMany(values, results.toVector)
      }

      protected def doBatched(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]) =
        preparedInsert(sql.head, ctx.session) { st =>
          st.clearParameters()
          for (value <- values) {
            a.converter.set(value, st, 0)
            st.addBatch()
          }
          val counts = st.executeBatch()
          retManyBatch(st, values, counts)
        }

      protected def doMultiRowStatement(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]) =
        preparedInsert(sql.head, ctx.session) { st =>
          st.clearParameters()
          val size = a.ibr.fields.length
          for ((value, idx) <- values.zipWithIndex)
            a.converter.set(value, st, idx * size)
          val count = st.executeUpdate()
          retManyMultiRowStatement(st, values, count)
        }

      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]): MultiInsertResult =
        rowsPerStatement match {
          case RowsPerStatement.One =>
            values match {
              case seq: IndexedSeq[?] if seq.length < 2 => doUnbatched(ctx, sql)
              case _ if !useBatchUpdates(ctx.session)   => doUnbatched(ctx, sql)
              case _                                    => doBatched(ctx, sql)
            }
          case RowsPerStatement.All => doMultiRowStatement(ctx, sql)
        }
    }

    class InsertOrUpdateAction(value: U) extends SimpleJdbcProfileAction[SingleInsertOrUpdateResult]("InsertOrUpdateAction",
      if(useServerSideUpsert) Vector(compiled.upsert.sql) else Vector(compiled.checkInsert.sql, compiled.updateInsert.sql, compiled.standardInsert.sql)) {

      private def tableHasPrimaryKey: Boolean =
        List(compiled.upsert, compiled.checkInsert, compiled.updateInsert)
          .filter(_ != null)
          .exists(artifacts =>
            artifacts.ibr.table.profileTable.asInstanceOf[Table[?]].primaryKeys.nonEmpty
              || artifacts.ibr.fields.exists(_.options.contains(PrimaryKey))
          )

      if (!tableHasPrimaryKey)
        throw new SlickException("InsertOrUpdate is not supported on a table without PK.")

      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]) = {
        def f: SingleInsertOrUpdateResult =
          if(useServerSideUpsert) nativeUpsert(value, sql.head)(ctx.session) else emulate(value, sql(0), sql(1), sql(2))(ctx.session)
        if(useTransactionForUpsert) {
          ctx.session.startInTransaction
          var done = false
          try {
            val res = f
            done = true
            ctx.session.endInTransaction(ctx.session.conn.commit())
            res
          } finally {
            if(!done)
              try ctx.session.endInTransaction(ctx.session.conn.rollback()) catch ignoreFollowOnError
          }
        } else f
      }

      protected def nativeUpsert(value: U, sql: String)(implicit session: JdbcBackend#Session): SingleInsertOrUpdateResult =
        preparedInsert(sql, session) { st =>
          st.clearParameters()
          compiled.upsert.converter.set(value, st, 0)
          val count = st.executeUpdate()
          retOneInsertOrUpdate(st, value, count)
        }

      protected def emulate(value: U, checkSql: String, updateSql: String, insertSql: String)(implicit session: JdbcBackend#Session): SingleInsertOrUpdateResult = {
        val found = preparedOther(checkSql, session) { st =>
          st.clearParameters()
          compiled.checkInsert.converter.set(value, st, 0)
          val rs = st.executeQuery()
          try rs.next() finally rs.close()
        }
        if(found) preparedOther(updateSql, session) { st =>
          st.clearParameters()
          compiled.updateInsert.converter.set(value, st, 0)
          st.executeUpdate()
          retOneInsertOrUpdateFromUpdate
        } else preparedInsert(insertSql, session) { st =>
          st.clearParameters()
          compiled.standardInsert.converter.set(value, st, 0)
          val count = st.executeUpdate()
          retOneInsertOrUpdateFromInsert(st, value, count)
        }
      }
    }

    class InsertOrUpdateAllAction(values: Iterable[U], rowsPerStatement: RowsPerStatement)
      extends MultiInsertAction(compiled.upsert, values, rowsPerStatement)

    class InsertQueryAction(sbr: SQLBuilder.Result, param: Any) extends SimpleJdbcProfileAction[QueryInsertResult]("InsertQueryAction", Vector(sbr.sql)) {
      def run(ctx: JdbcBackend#JdbcActionContext, sql: Vector[String]) = preparedInsert(sql.head, ctx.session) { st =>
        st.clearParameters()
        sbr.setter(st, 1, param)
        retQuery(st, st.executeUpdate())
      }
    }
  }

  protected class CountingInsertActionComposerImpl[U](compiled: CompiledInsert) extends InsertActionComposerImpl[U](compiled) with CountingInsertActionComposer[U] {
    def returning[RT, RU, C[_]](value: Query[RT, RU, C]): ReturningInsertActionComposer[U, RU] =
      createReturningInsertActionComposer[U, RU, RU](compiled, value.toNode, (_, r) => r)

    protected def retOne(st: Statement, value: U, updateCount: Int) = updateCount
    protected def retOneInsertOrUpdate(st: Statement, value: U, updateCount: Int) = 1
    protected def retOneInsertOrUpdateFromInsert(st: Statement, value: U, updateCount: Int) = 1
    protected def retOneInsertOrUpdateFromUpdate = 1
    protected def retQuery(st: Statement, updateCount: Int) = updateCount
    protected def retMany(values: Iterable[U], individual: Seq[SingleInsertResult]): Some[Int] = Some(individual.sum)
    protected def retManyMultiRowStatement(st: Statement, values: Iterable[U], updateCount: Int): Some[Int] =
      Some(updateCount)

    protected def retManyBatch(st: Statement, values: Iterable[U], updateCounts: Array[Int]) = {
      var unknown = false
      var count = 0
      for((res, idx) <- updateCounts.zipWithIndex) res match {
        case Statement.SUCCESS_NO_INFO => unknown = true
        case Statement.EXECUTE_FAILED => throw new SlickException("Failed to insert row #" + (idx+1))
        case i => count += i
      }
      if(unknown) None else Some(count)
    }
  }

  protected class ReturningInsertActionComposerImpl[U, QR, RU](compiled: CompiledInsert, val keys: Node, val mux: (U, QR) => RU) extends InsertActionComposerImpl[U](compiled) with ReturningInsertActionComposer[U, RU] {
    def into[R](f: (U, RU) => R): IntoInsertActionComposer[U, R] =
      createReturningInsertActionComposer[U, QR, R](compiled, keys, (v, r) => f(v, mux(v, r)))

    override protected def useServerSideUpsert = self.useServerSideUpsertReturning
    override protected def useTransactionForUpsert = self.useTransactionForUpsertReturning

    protected def checkInsertOrUpdateKeys: Unit =
      if(keyReturnOther) throw new SlickException("Only a single AutoInc column may be returned from an insertOrUpdate call")

    protected def buildKeysResult(st: Statement): Invoker[QR] =
      ResultSetInvoker[QR](_ => st.getGeneratedKeys)(pr => keyConverter.read(pr.rs).asInstanceOf[QR])

    // Returning keys from batch inserts is generally not supported
    override protected def useBatchUpdates(implicit session: JdbcBackend#Session) = false

    protected lazy val (keyColumns, keyConverter, keyReturnOther) = compiled.buildReturnColumns(keys)

    override protected def preparedInsert[T](sql: String, session: JdbcBackend#Session)(f: PreparedStatement => T) =
      session.withPreparedInsertStatement(sql, keyColumns.toArray)(f)

    protected def retOne(st: Statement, value: U, updateCount: Int) = mux(value, buildKeysResult(st).first(null))

    protected def retMany(values: Iterable[U], individual: Seq[SingleInsertResult]): Seq[SingleInsertResult] = individual

    protected def retManyMultiRowStatement(st: Statement, values: Iterable[U], updateCount: Int): Seq[RU] = {
      if (capabilities.contains(JdbcCapabilities.returnMultipleInsertKey))
        values.lazyZip(buildKeysResult(st).buildColl[Vector](null, implicitly)).map(mux).toSeq
      else
        Nil
    }

    protected def retManyBatch(st: Statement, values: Iterable[U], updateCounts: Array[Int]): Seq[RU] =
      values.lazyZip(buildKeysResult(st).buildColl[Vector](null, implicitly)).map(mux).toSeq

    protected def retQuery(st: Statement, updateCount: Int) =
      buildKeysResult(st).buildColl[Vector](null, implicitly).asInstanceOf[QueryInsertResult] // Not used with "into"

    protected def retOneInsertOrUpdate(st: Statement, value: U, updateCount: Int): SingleInsertOrUpdateResult =
      if(updateCount != 1) None else buildKeysResult(st).firstOption(null).map(r => mux(value, r))

    protected def retOneInsertOrUpdateFromInsert(st: Statement, value: U, updateCount: Int): SingleInsertOrUpdateResult =
      Some(mux(value, buildKeysResult(st).first(null)))

    protected def retOneInsertOrUpdateFromUpdate: SingleInsertOrUpdateResult = None
  }
}
object JdbcActionComponent {
  trait MultipleRowsPerStatementSupport extends JdbcActionComponent { self: JdbcProfile =>
    override type RowsPerStatement = slick.jdbc.RowsPerStatement
    override def defaultRowsPerStatement: RowsPerStatement.All.type = RowsPerStatement.All
  }
  trait OneRowPerStatementOnly extends JdbcActionComponent { self: JdbcProfile =>
    override type RowsPerStatement = slick.jdbc.RowsPerStatement.One.type
    override def defaultRowsPerStatement = slick.jdbc.RowsPerStatement.One
  }
}
