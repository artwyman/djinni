package djinni

import djinni.ast._
import djinni.generatorTools._
import djinni.meta._

class ObjcMarshal(spec: Spec) extends Marshal(spec) {

  override def typename(tm: MExpr): String = {
    val (name, _) = toObjcType(tm)
    name
  }
  def typename(name: String, ty: TypeDef): String = idObjc.ty(name)

  override def fqTypename(tm: MExpr): String = typename(tm)
  def fqTypename(name: String, ty: TypeDef): String = typename(name, ty)

  def nullability(tm: MExpr): Option[String] = {
    tm.base match {
      case MOptional => Some("nullable")
      case MPrimitive(_,_,_,_,_,_,_,_) => None
      case d: MDef => d.defType match {
        case DEnum => None
        case DInterface => Some("nullable")
        case DRecord => Some("nonnull")
      }
      case _ => Some("nonnull")
    }
  }

  override def paramType(tm: MExpr): String = {
    nullability(tm).fold("")(_ + " ") + toObjcParamType(tm)
  }
  override def fqParamType(tm: MExpr): String = paramType(tm)

  override def returnType(ret: Option[TypeRef]): String = ret.fold("void")((t: TypeRef) => nullability(t.resolved).fold("")(_ + " ") + toObjcParamType(t.resolved))
  override def fqReturnType(ret: Option[TypeRef]): String = returnType(ret)

  override def fieldType(tm: MExpr): String = toObjcParamType(tm)
  override def fqFieldType(tm: MExpr): String = toObjcParamType(tm)

  override def toCpp(tm: MExpr, expr: String): String = throw new AssertionError("direct objc to cpp conversion not possible")
  override def fromCpp(tm: MExpr, expr: String): String = throw new AssertionError("direct cpp to objc conversion not possible")

  def references(m: Meta, exclude: String = ""): Seq[SymbolReference] = m match {
    case o: MOpaque =>
      List(ImportRef("<Foundation/Foundation.h>"))
    case d: MDef => d.defType match {
      case DEnum =>
        List(ImportRef(q(spec.objcIncludePrefix + headerName(d.name))))
      case DInterface =>
        val ext = d.body.asInstanceOf[Interface].ext
        if (ext.cpp) {
          List(ImportRef("<Foundation/Foundation.h>"), DeclRef(s"@class ${typename(d.name, d.body)};", None))
        }
        else if (ext.objc) {
          List(ImportRef("<Foundation/Foundation.h>"), DeclRef(s"@protocol ${typename(d.name, d.body)};", None))
        }
        else {
          List()  
        }
      case DRecord =>
        val r = d.body.asInstanceOf[Record]
        val prefix = if (r.ext.objc) "../" else ""
        List(ImportRef(q(spec.objcIncludePrefix + prefix + headerName(d.name))))
    }
    case p: MParam => List()
  }

  def headerName(ident: String): String = idObjc.ty(ident) + "." + spec.objcHeaderExt

  // Return value: (Type_Name, Is_Class_Or_Not)
  def toObjcType(ty: TypeRef): (String, Boolean) = toObjcType(ty.resolved, false)
  def toObjcType(ty: TypeRef, needRef: Boolean): (String, Boolean) = toObjcType(ty.resolved, needRef)
  def toObjcType(tm: MExpr): (String, Boolean) = toObjcType(tm, false)
  def toObjcType(tm: MExpr, needRef: Boolean): (String, Boolean) = {
    def f(tm: MExpr, needRef: Boolean): (String, Boolean) = {
      tm.base match {
        case MOptional =>
          // We use "nil" for the empty optional.
          assert(tm.args.size == 1)
          val arg = tm.args.head
          arg.base match {
            case MOptional => throw new AssertionError("nested optional?")
            case m => f(arg, true)
          }
        case o =>
          val base = o match {
            case p: MPrimitive => if (needRef) (p.objcBoxed, true) else (p.objcName, false)
            case MString => ("NSString", true)
            case MDate => ("NSDate", true)
            case MBinary => ("NSData", true)
            case MOptional => throw new AssertionError("optional should have been special cased")
            case MList => ("NSArray", true)
            case MSet => ("NSSet", true)
            case MMap => ("NSDictionary", true)
            case d: MDef => d.defType match {
              case DEnum => if (needRef) ("NSNumber", true) else (idObjc.ty(d.name), false)
              case DRecord => (idObjc.ty(d.name), true)
              case DInterface =>
                val ext = d.body.asInstanceOf[Interface].ext
                (idObjc.ty(d.name), true)
            }
            case p: MParam => throw new AssertionError("Parameter should not happen at Obj-C top level")
          }
          base
      }
    }
    f(tm, needRef)
  }

  def toObjcParamType(tm: MExpr): String = {
    val (name, needRef) = toObjcType(tm)
    val param = name + (if(needRef) " *" else "")
    tm.base match {
      case d: MDef => d.body match {
        case i: Interface => if(i.ext.objc) s"id<$name>" else param
        case _ => param
      }
      case MOptional => tm.args.head.base match {
        case d: MDef => d.body match {
          case i: Interface => if(i.ext.objc) s"id<$name>" else param
          case _ => param
        }
        case _ => param
      }
      case _ => param
    }
  }

}
