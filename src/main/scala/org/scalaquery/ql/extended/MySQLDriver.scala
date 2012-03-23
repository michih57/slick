package org.scalaquery.ql.extended

import org.scalaquery.SQueryException
import org.scalaquery.ql._
import org.scalaquery.ql.basic._
import org.scalaquery.ast._

class MySQLDriver extends ExtendedProfile { self =>

  type ImplicitT = ExtendedImplicitConversions[MySQLDriver]
  type TypeMapperDelegatesT = MySQLTypeMapperDelegates

  val Implicit = new ExtendedImplicitConversions[MySQLDriver] {
    implicit val scalaQueryDriver = self
  }

  val typeMapperDelegates = new MySQLTypeMapperDelegates
  override val sqlUtils = new MySQLSQLUtils

  override def createQueryBuilder(query: Query[_, _]) = new MySQLQueryBuilder(query, this)
  override def buildTableDDL(table: AbstractBasicTable[_]): DDL = new MySQLDDLBuilder(table, this).buildDDL
  override def buildSequenceDDL(seq: Sequence[_]): DDL = new MySQLSequenceDDLBuilder(seq, this).buildDDL
}

object MySQLDriver extends MySQLDriver

class MySQLTypeMapperDelegates extends BasicTypeMapperDelegates {
  override val stringTypeMapperDelegate = new BasicTypeMapperDelegates.StringTypeMapperDelegate {
    override def valueToSQLLiteral(value: String) = if(value eq null) "NULL" else {
      val sb = new StringBuilder
      sb append '\''
      for(c <- value) c match {
        case '\'' => sb append "\\'"
        case '"' => sb append "\\\""
        case 0 => sb append "\\0"
        case 26 => sb append "\\Z"
        case '\b' => sb append "\\b"
        case '\n' => sb append "\\n"
        case '\r' => sb append "\\r"
        case '\t' => sb append "\\t"
        case '\\' => sb append "\\\\"
        case _ => sb append c
      }
      sb append '\''
      sb.toString
    }
  }

  override val uuidTypeMapperDelegate = new BasicTypeMapperDelegates.UUIDTypeMapperDelegate {
    override def sqlType = java.sql.Types.BINARY
    override def sqlTypeName = "BINARY(16)"
  }
}

class MySQLQueryBuilder(_query: Query[_, _], profile: MySQLDriver) extends BasicQueryBuilder(_query, profile) {

  import ExtendedQueryOps._
  import profile.sqlUtils._

  override protected val scalarFrom = Some("DUAL")
  override protected val supportsCast = false

  override protected def innerExpr(c: Node): Unit = c match {
    case EscFunction("concat", l, r) => b += "concat("; expr(l); b += ','; expr(r); b += ')'
    case Sequence.Nextval(seq) => b += quoteIdentifier(seq.name + "_nextval") += "()"
    case Sequence.Currval(seq) => b += quoteIdentifier(seq.name + "_currval") += "()"
    case _ => super.innerExpr(c)
  }

  override protected def appendTakeDropClause(take: Option[Int], drop: Option[Int]) = (take, drop) match {
    case (Some(t), Some(d)) => b += " LIMIT " += d += ',' += t
    case (Some(t), None) => b += " LIMIT " += t
    case (None, Some(d)) => b += " LIMIT " += d += ",18446744073709551615"
    case _ =>
  }

  override protected def appendOrdering(n: Node, o: Ordering) {
    if(o.nulls.last && !o.direction.desc) {
      b += "isnull("
      expr(n)
      b += "),"
    } else if(o.nulls.first && o.direction.desc) {
      b += "isnull("
      expr(n)
      b += ") desc,"
    }
    expr(n)
    if(o.direction.desc) b += " desc"
  }
}

class MySQLDDLBuilder(table: AbstractBasicTable[_], profile: MySQLDriver) extends BasicDDLBuilder(table, profile) {
  override protected def dropForeignKey(fk: ForeignKey[_ <: AbstractTable[_], _]) = {
    "ALTER TABLE " + table.tableName + " DROP FOREIGN KEY " + fk.name
  }
}

class MySQLSequenceDDLBuilder[T](seq: Sequence[T], profile: MySQLDriver) extends BasicSequenceDDLBuilder(seq, profile) {
  import profile.sqlUtils._

  override def buildDDL: DDL = {
    import seq.integral._
    val sqlType = seq.typeMapper(profile).sqlTypeName
    val t = sqlType + " not null"
    val increment = seq._increment.getOrElse(one)
    val desc = increment < zero
    val minValue = seq._minValue getOrElse (if(desc) fromInt(java.lang.Integer.MIN_VALUE) else one)
    val maxValue = seq._maxValue getOrElse (if(desc) fromInt(-1) else fromInt(java.lang.Integer.MAX_VALUE))
    val start = seq._start.getOrElse(if(desc) maxValue else minValue)
    val beforeStart = start - increment
    if(!seq._cycle && (seq._minValue.isDefined && desc || seq._maxValue.isDefined && !desc))
      throw new SQueryException("Sequences with limited size and without CYCLE are not supported by MySQLDriver's sequence emulation")
    val incExpr = if(seq._cycle) {
      if(desc) "if(id-"+(-increment)+"<"+minValue+","+maxValue+",id-"+(-increment)+")"
      else "if(id+"+increment+">"+maxValue+","+minValue+",id+"+increment+")"
    } else {
      "id+("+increment+")"
    }
    //TODO Implement currval function
    new DDL {
      val createPhase1 = Iterable(
        "create table " + quoteIdentifier(seq.name + "_seq") + " (id " + t + ")",
        "insert into " + quoteIdentifier(seq.name + "_seq") + " values (" + beforeStart + ")",
        "create function " + quoteIdentifier(seq.name + "_nextval") + "() returns " + sqlType + " begin update " +
          quoteIdentifier(seq.name + "_seq") + " set id=last_insert_id(" + incExpr + "); return last_insert_id(); end")
      val createPhase2 = Nil
      val dropPhase1 = Nil
      val dropPhase2 = Iterable(
        "drop function " + quoteIdentifier(seq.name + "_nextval"),
        "drop table " + quoteIdentifier(seq.name + "_seq"))
    }
  }
}

class MySQLSQLUtils extends BasicSQLUtils {
  override def quoteIdentifier(id: String) = '`' + id + '`'
}
