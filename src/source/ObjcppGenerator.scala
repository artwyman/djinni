/**
  * Copyright 2014 Dropbox, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package djinni

import djinni.ast.Record.DerivingType
import djinni.ast._
import djinni.generatorTools._
import djinni.meta._
import djinni.syntax.Error
import djinni.writer.IndentWriter

import scala.collection.mutable
import scala.collection.parallel.immutable

class ObjcppGenerator(spec: Spec) extends Generator(spec) {

  val objcMarshal = new ObjcMarshal(spec)
  val objcppMarshal = new ObjcppMarshal(spec)
  val cppMarshal = new CppMarshal(spec)

  class ObjcRefs() {
    var body = mutable.TreeSet[String]()
    var header = mutable.TreeSet[String]()
    var privHeader = mutable.TreeSet[String]()

    def find(ty: TypeRef) { find(ty.resolved) }
    def find(tm: MExpr) {
      tm.args.map(find).mkString("<", ",", ">")
      tm.base match {
        case o: MOpaque =>
          header.add("#import <Foundation/Foundation.h>")
          body.add("#import " + q(spec.objcBaseLibIncludePrefix + "DJIMarshal+Private.h"))
        case d: MDef => d.defType match {
          case DEnum =>
            header.add("#import " + q(spec.objcIncludePrefix + headerName(d.name)))
            body.add("#import " + q(spec.objcBaseLibIncludePrefix + "DJIMarshal+Private.h"))
          case DInterface =>
            val ext = d.body.asInstanceOf[Interface].ext
            if (ext.cpp) {
              header.add("@class " + objcMarshal.typename(tm) + ";")
              body.add("#import " + q(spec.objcIncludePrivatePrefix + privateHeaderName(d.name)))
            }
            if (ext.objc) {
              header.add("@protocol " + objcMarshal.typename(tm) + ";")
              body.add("#import " + q(spec.objcIncludePrivatePrefix + privateHeaderName(d.name)))
            }
          case DRecord =>
            val r = d.body.asInstanceOf[Record]
            val prefix = if (r.ext.objc) "../" else ""
            header.add("@class " + objcMarshal.typename(tm) + ";")
            body.add("#import " + q(spec.objcIncludePrivatePrefix + prefix + privateHeaderName(d.name)))
        }
        case p: MParam =>
      }
    }
  }

  private def arcAssert(w: IndentWriter) = w.wl("static_assert(__has_feature(objc_arc), " + q("Djinni requires ARC to be enabled for this file") + ");")

  override def generateEnum(origin: String, ident: Ident, doc: Doc, e: Enum) {
    // No generation required
  }

  def headerName(ident: String): String = idObjc.ty(ident) + "." + spec.objcHeaderExt
  def privateHeaderName(ident: String): String = idObjc.ty(ident) + "+Private." + spec.objcHeaderExt
  def bodyName(ident: String): String = idObjc.ty(ident) + "." + spec.objcExt

  def writeObjcConstVariable(w: IndentWriter, c: Const, s: String): Unit = c.ty.resolved.base match {
    // MBinary | MList | MSet | MMap are not allowed for constants.
    // Primitives should be `const type`. All others are pointers and should be `type * const`
    case t: MPrimitive => w.w(s"const ${objcMarshal.fqFieldType(c.ty)} $s${idObjc.const(c.ident)}")
    case _ => w.w(s"${objcMarshal.fqFieldType(c.ty)} const $s${idObjc.const(c.ident)}")
  }

  def generateObjcConstants(w: IndentWriter, consts: Seq[Const], selfName: String) = {
    def boxedPrimitive(ty: TypeRef): String = {
      val (_, needRef) = toObjcType(ty)
      if (needRef) "@" else ""
    }
    def writeObjcConstValue(w: IndentWriter, ty: TypeRef, v: Any): Unit = v match {
      case l: Long => w.w(boxedPrimitive(ty) + l.toString)
      case d: Double => w.w(boxedPrimitive(ty) + d.toString)
      case b: Boolean => w.w(boxedPrimitive(ty) + (if (b) "YES" else "NO"))
      case s: String => w.w("@" + s)
      case e: EnumValue => w.w(idObjc.enum(e.ty + "_" + e.name))
      case v: ConstRef => w.w(selfName + idObjc.const (v.name))
      case z: Map[_, _] => { // Value is record
        val recordMdef = ty.resolved.base.asInstanceOf[MDef]
        val record = recordMdef.body.asInstanceOf[Record]
        val vMap = z.asInstanceOf[Map[String, Any]]
        val head = record.fields.head
                w.w(s"[[${idObjc.ty(recordMdef.name)} alloc] initWith${IdentStyle.camelUpper(head.ident)}:")
        writeObjcConstValue(w, head.ty, vMap.apply(head.ident))
        w.nestedN(2) {
          val skipFirst = SkipFirst()
          for (f <- record.fields) skipFirst {
            w.wl
            w.w(s"${idObjc.field(f.ident)}:")
            writeObjcConstValue(w, f.ty, vMap.apply(f.ident))
          }
        }
        w.w("]")
      }
    }

    w.wl("#pragma clang diagnostic push")
    w.wl("#pragma clang diagnostic ignored " + q("-Wunused-variable"))
    for (c <- consts) {
      w.wl
      writeObjcConstVariable(w, c, selfName)
      w.w(s" = ")
      writeObjcConstValue(w, c.ty, c.value)
      w.wl(";")
    }
    w.wl
    w.wl("#pragma clang diagnostic pop")
  }

  override def generateInterface(origin: String, ident: Ident, doc: Doc, typeParams: Seq[TypeParam], i: Interface) {
    val refs = new ObjcRefs()
    i.methods.map(m => {
      m.params.map(p => refs.find(p.ty))
      m.ret.foreach(refs.find)
    })
    i.consts.map(c => {
      refs.find(c.ty)
    })

    val self = objcMarshal.typename(ident, i)
    val cppSelf = cppMarshal.fqTypename(ident, i)

    refs.privHeader.add("#include <memory>")
    refs.privHeader.add("!#include " + q(spec.objcIncludeCppPrefix + spec.cppFileIdentStyle(ident) + "." + spec.cppHeaderExt))
    refs.body.add("#include <vector>")
    refs.body.add("!#import " + q(spec.objcIncludePrefix + headerName(ident)))

    def writeObjcFuncDecl(method: Interface.Method, w: IndentWriter) {
      val label = if (method.static) "+" else "-"
      val ret = objcMarshal.fqReturnType(method.ret)
      w.w(s"$label ($ret)${idObjc.method(method.ident)}")
      val skipFirst = SkipFirst()
      for (p <- method.params) {
        skipFirst { w.w(s" ${idObjc.local(p.ident)}") }
        w.w(s":(${objcMarshal.paramType(p.ty)})${idObjc.local(p.ident)}")
      }
    }

    val helperClass = objcppMarshal.helperClass(ident)
    val fqHelperClass = objcppMarshal.fqHelperClass(ident)

    writeObjcFile(privateHeaderName(ident.name), origin, refs.privHeader, w => {
      arcAssert(w)
      w.wl
      w.wl((if(i.ext.objc) "@protocol " else "@class ") + self + ";")
      w.wl
      wrapNamespace(w, Some(spec.objcppNamespace), w => {
        w.wl(s"struct $helperClass")
        w.bracedSemi {
          w.wl(s"using CppType = std::shared_ptr<$cppSelf>;")
          w.wl("using ObjcType = " + (if(i.ext.objc) s"id<$self>" else s"$self*") + ";");
          w.wl
          w.wl(s"static CppType toCpp(ObjcType objc);")
          w.wl(s"static ObjcType fromCpp(const CppType& cpp);")
        }
      })
      w.wl
    })

    if (i.ext.cpp) {
      refs.body.add("!#import " + q(spec.objcIncludePrivatePrefix + privateHeaderName(ident.name)))
      refs.body.add("#import " + q(spec.objcBaseLibIncludePrefix + "DJICppWrapperCache+Private.h"))
      refs.body.add("#include <utility>")
      refs.body.add("#import " + q(spec.objcBaseLibIncludePrefix + "DJIError.h"))
      refs.body.add("#import " + q(spec.objcBaseLibIncludePrefix + "DJIDate.h"))
      refs.body.add("#include <exception>")

      writeObjcFile(bodyName(ident.name), origin, refs.body, w => {
        arcAssert(w)
        if (i.consts.nonEmpty) generateObjcConstants(w, i.consts, self)
        w.wl
        w.wl(s"@interface $self ()")
        w.wl
        w.wl(s"@property (nonatomic, readonly) djinni::DbxCppWrapperCache<$cppSelf>::Handle cppRef;")
        w.wl
        w.wl(s"- (id)initWithCpp:(const std::shared_ptr<$cppSelf>&)cppRef;")
        w.wl
        w.wl("@end")
        w.wl
        wrapNamespace(w, Some(spec.objcppNamespace), w => {
          w.wl(s"auto $helperClass::toCpp(ObjcType objc) -> CppType")
          w.braced {
            w.wl(s"return objc ? objc.cppRef.get() : nullptr;")
          }
          w.wl
          w.wl(s"auto $helperClass::fromCpp(const CppType& cpp) -> ObjcType")
          w.braced {
            w.wl(s"return !cpp ? nil : djinni::DbxCppWrapperCache<$cppSelf>::getInstance()->get(cpp, [] (const auto& p)").bracedEnd(");") {
              w.wl(s"return [[$self alloc] initWithCpp:p];")
            }
          }
        })
        w.wl
        w.wl(s"@implementation $self")
        w.wl
        w.wl(s"- (id)initWithCpp:(const std::shared_ptr<$cppSelf>&)cppRef")
        w.braced {
          w.w("if (self = [super init])").braced {
            w.wl("_cppRef.assign(cppRef);")
          }
          w.wl("return self;")
        }
        for (m <- i.methods) {
          w.wl
          writeObjcFuncDecl(m, w)
          w.braced {
            w.w("try").bracedEnd(" DJINNI_TRANSLATE_EXCEPTIONS()") {
              for (p <- m.params) {
                translateObjcTypeToCpp(idObjc.local("cpp_" + p.ident.name), idObjc.local(p.ident.name), p.ty, w)
              }
              val params = m.params.map(p => "std::move(" + idObjc.local("cpp_" + p.ident.name) + ")").mkString("(", ", ", ")")
              val cppRef = if (!m.static) "_cppRef.get()->" else  cppSelf + "::"
              m.ret match {
                case None =>
                  w.wl(s"$cppRef${idCpp.method(m.ident)}$params;")
                case Some(r) =>
                  w.wl(s"${cppMarshal.fqTypename(r)} cppRet = $cppRef${idCpp.method(m.ident)}$params;")
                  translateCppTypeToObjc("objcRet", "cppRet", r, true, w)
                  w.wl("return objcRet;")
              }
            }
          }
        }
        w.wl
        w.wl("@end")
      })
    }

    if (i.ext.objc) {
      val objcExtSelf = objcppMarshal.helperClass("objc_proxy")
      refs.body.add("#import " + q(spec.objcBaseLibIncludePrefix + "DJIObjcWrapperCache+Private.h"))
      refs.body.add("!#import " + q(spec.objcIncludePrivatePrefix + privateHeaderName(ident.name)))

      writeObjcFile(bodyName(ident.name), origin, refs.body, w => {
        arcAssert(w)
        w.wl
        wrapAnonymousNamespace(w, w => {
          w.wl(s"class $objcExtSelf final")
          w.wl(s": public $cppSelf")
          w.wl(s", public ::djinni::DbxObjcWrapperCache<$objcExtSelf>::Handle") // Use base class to avoid name conflicts with user-defined methods having the same name as this new data member
          w.bracedSemi {
            w.wlOutdent("public:")
            w.wl("using Handle::Handle;")
            for (m <- i.methods) {
              val ret = cppMarshal.fqReturnType(m.ret)
              val params = m.params.map(p => cppMarshal.fqParamType(p.ty) + " " + idCpp.local(p.ident))
              w.wl(s"$ret ${idCpp.method(m.ident)} ${params.mkString("(", ", ", ")")} override;")
            }
          }
        })
        w.wl
        wrapNamespace(w, Some(spec.objcppNamespace), w => {
          w.wl(s"auto $helperClass::toCpp(ObjcType objc) -> CppType")
          w.braced {
            w.wl(s"return objc ? djinni::DbxObjcWrapperCache<$objcExtSelf>::getInstance()->get(objc) : nullptr;")
          }
          w.wl
          w.wl(s"auto $helperClass::fromCpp(const CppType& cpp) -> ObjcType")
          w.braced {
            w.wl(s"assert(!cpp || dynamic_cast<$objcExtSelf*>(cpp.get()));")
            w.wl(s"return cpp ? static_cast<$objcExtSelf&>(*cpp).Handle::get() : nil;")
          }
        })
        for (m <- i.methods) {
          w.wl
          val ret = cppMarshal.fqReturnType(m.ret)
          val params = m.params.map(p => cppMarshal.fqParamType(p.ty) + " " + idCpp.local(p.ident))
          w.wl(s"$ret $objcExtSelf::${idCpp.method(m.ident)} ${params.mkString("(", ", ", ")")}").braced {
            w.w("@autoreleasepool").braced {
              m.params.foreach(p =>
                translateCppTypeToObjc(idCpp.local("cpp_" + p.ident.name), idCpp.local(p.ident), p.ty, true, w))
              m.ret.fold()(r => w.w(objcMarshal.fqFieldType(r) + " objcRet = "))
              w.w("[Handle::get() " + idObjc.method(m.ident))
              val skipFirst = SkipFirst()
              for (p <- m.params) {
                skipFirst { w.w(" " + idObjc.local(p.ident)) }
                w.w(":" + idCpp.local("cpp_" + p.ident.name))
              }
              w.wl("];")
              m.ret.fold()(r => {
                translateObjcTypeToCpp("cppRet", "objcRet", r, w)
                w.wl("return cppRet;")
              })
            }
          }
        }
      })
    }
  }

  override def generateRecord(origin: String, ident: Ident, doc: Doc, params: Seq[TypeParam], r: Record) {
    val refs = new ObjcRefs()
    for (c <- r.consts)
      refs.find(c.ty)
    for (f <- r.fields)
      refs.find(f.ty)

    val objcName = ident.name + (if (r.ext.objc) "_base" else "")
    val noBaseSelf = objcMarshal.typename(ident, r) // Used for constant names
    val self = objcMarshal.typename(objcName, r)
    val cppSelf = cppMarshal.fqTypename(ident, r)

    refs.header.add("#import <Foundation/Foundation.h>")

    refs.privHeader.add("#import <Foundation/Foundation.h>")
    refs.privHeader.add("!#import " + q(spec.objcIncludePrefix + headerName(objcName)))
    refs.privHeader.add("!#include " + q(spec.objcIncludeCppPrefix + spec.cppFileIdentStyle(ident) + "." + spec.cppHeaderExt))

    refs.body.add("#import <Foundation/Foundation.h>")
    refs.body.add("#include <utility>")
    refs.body.add("#include <vector>")
    refs.body.add("#import " + q(spec.objcBaseLibIncludePrefix + "DJIDate.h"))
    refs.body.add("!#import " + q(spec.objcIncludePrivatePrefix + privateHeaderName(objcName)))

    if (r.ext.objc) {
      refs.body.add("#import " + q(spec.objcIncludePrefix + "../" + headerName(ident)))
      refs.privHeader.add("#import " + q(spec.objcIncludePrefix + "../" + headerName(ident)))
      refs.header.add(s"@class ${objcMarshal.typename(ident, r)};")
    }

    def checkMutable(tm: MExpr): Boolean = tm.base match {
      case MOptional => checkMutable(tm.args.head)
      case MString => true
      case MBinary => true
      case _ => false
    }

    writeObjcFile(privateHeaderName(objcName), origin, refs.privHeader, w => {
      w.wl(s"@interface $self ()")
      w.wl
      // Deep copy constructor
      w.wl(s"- (id)${idObjc.method("init_with_cpp_" + ident.name)}:(const $cppSelf &)${idObjc.local(ident)};")
      w.wl(s"- ($cppSelf)${idObjc.method("cpp_" + ident.name)};")
      w.wl
      w.wl("@end")
    })

    writeObjcFile(bodyName(objcName), origin, refs.body, w => {
      if (r.consts.nonEmpty) generateObjcConstants(w, r.consts, noBaseSelf)
      arcAssert(w)
      w.wl
      w.wl(s"@implementation $self")
      w.wl
      w.wl(s"- (id)${idObjc.method("init_with_" + ident.name)}:($noBaseSelf *)${idObjc.local(ident)}")
      w.braced {
        w.w("if (self = [super init])").braced {
          for (f <- r.fields) {
            copyObjcValue(s"_${idObjc.field(f.ident)}", s"${idObjc.local(ident)}.${idObjc.field(f.ident)}", f.ty, w)
          }
        }
        w.wl("return self;")
      }
      w.wl
      // Constructor from all fields (not copying)
      if (!r.fields.isEmpty) {
        val head = r.fields.head
        val skipFirst = SkipFirst()
        w.w(s"- (id)${idObjc.method("init_with_" + head.ident.name)}:(${objcMarshal.paramType(head.ty)})${idObjc.field(head.ident)}")
        for (f <- r.fields) skipFirst {
          w.w(s" ${idObjc.field(f.ident.name)}:(${objcMarshal.paramType(f.ty)})${idObjc.local(f.ident)}")
        }
        w.wl
        w.braced {
          w.w("if (self = [super init])").braced {
            for (f <- r.fields) {
              if (checkMutable(f.ty.resolved))
                w.wl(s"_${idObjc.field(f.ident)} = [${idObjc.local(f.ident)} copy];")
              else
                w.wl(s"_${idObjc.field(f.ident)} = ${idObjc.local(f.ident)};")
            }
          }
          w.wl("return self;")
        }
        w.wl
      }
      // Cpp -> Objc translator
      w.wl(s"- (id)${idObjc.method("init_with_cpp_" + ident.name)}:(const $cppSelf &)${idObjc.local(ident)}")
      w.braced {
        w.w("if (self = [super init])").braced {
          for (f <- r.fields) {
            translateCppTypeToObjc("_" + idObjc.field(f.ident), idObjc.local(ident) + "." + idCpp.field(f.ident), f.ty, false, w)
          }
        }
        w.wl("return self;")
      }
      w.wl
      // Objc -> Cpp translator
      w.wl(s"- ($cppSelf)${idObjc.method("cpp_" + ident.name)}")
      w.braced {
        for (f <- r.fields) {
          translateObjcTypeToCpp(idObjc.local(f.ident), "_" + idObjc.field(f.ident), f.ty, w)
        }
        val skipFirst = SkipFirst()
        w.wl(s"return $cppSelf(").nestedN(2) {
          for (f <- r.fields)
          {
            skipFirst { w.wl(",") }
            w.w("std::move(" + idObjc.local(f.ident) + ")")
          }
        }
        w.wl(");")
      }

      if (r.derivingTypes.contains(DerivingType.Eq)) {
        w.wl("- (BOOL)isEqual:(id)other")
        w.braced {
          w.w(s"if (![other isKindOfClass:[$self class]])").braced {
            w.wl("return NO;")
          }
          w.wl(s"$self *typedOther = ($self *)other;")
          val skipFirst = SkipFirst()
          w.w(s"return ").nestedN(2) {
            for (f <- r.fields) {
              skipFirst { w.wl(" &&") }
              f.ty.resolved.base match {
                case MBinary => w.w(s"[self.${idObjc.field(f.ident)} isEqualToData:typedOther.${idObjc.field(f.ident)}]")
                case MList => w.w(s"[self.${idObjc.field(f.ident)} isEqualToArray:typedOther.${idObjc.field(f.ident)}]")
                case MSet => w.w(s"[self.${idObjc.field(f.ident)} isEqualToSet:typedOther.${idObjc.field(f.ident)}]")
                case MMap => w.w(s"[self.${idObjc.field(f.ident)} isEqualToDictionary:typedOther.${idObjc.field(f.ident)}]")
                case MOptional =>
                  f.ty.resolved.args.head.base match {
                    case df: MDef if df.defType == DEnum =>
                      w.w(s"self.${idObjc.field(f.ident)} == typedOther.${idObjc.field(f.ident)}")
                    case _ =>
                      w.w(s"((self.${idObjc.field(f.ident)} == nil && typedOther.${idObjc.field(f.ident)} == nil) || ")
                      w.w(s"(self.${idObjc.field(f.ident)} != nil && [self.${idObjc.field(f.ident)} isEqual:typedOther.${idObjc.field(f.ident)}]))")
                  }
                case MString => w.w(s"[self.${idObjc.field(f.ident)} isEqualToString:typedOther.${idObjc.field(f.ident)}]")
                case t: MPrimitive => w.w(s"self.${idObjc.field(f.ident)} == typedOther.${idObjc.field(f.ident)}")
                case df: MDef => df.defType match {
                  case DRecord => w.w(s"[self.${idObjc.field(f.ident)} isEqual:typedOther.${idObjc.field(f.ident)}]")
                  case DEnum => w.w(s"self.${idObjc.field(f.ident)} == typedOther.${idObjc.field(f.ident)}")
                  case _ => throw new AssertionError("Unreachable")
                }
                case _ => throw new AssertionError("Unreachable")
              }
            }
          }
          w.wl(";")
        }
      }

      def generatePrimitiveOrder(ident: Ident, w: IndentWriter): Unit = {
        w.wl(s"if (self.${idObjc.field(ident)} < other.${idObjc.field(ident)}) {").nested {
          w.wl(s"tempResult = NSOrderedAscending;")
        }
        w.wl(s"} else if (self.${idObjc.field(ident)} > other.${idObjc.field(ident)}) {").nested {
          w.wl(s"tempResult = NSOrderedDescending;")
        }
        w.wl(s"} else {").nested {
          w.wl(s"tempResult = NSOrderedSame;")
        }
        w.wl("}")
      }
      if (r.derivingTypes.contains(DerivingType.Ord)) {
        w.wl(s"- (NSComparisonResult)compare:($self *)other")
        w.braced {
          w.wl("NSComparisonResult tempResult;")
          for (f <- r.fields) {
            f.ty.resolved.base match {
              case MString => w.wl(s"tempResult = [self.${idObjc.field(f.ident)} compare:other.${idObjc.field(f.ident)}];")
              case t: MPrimitive => generatePrimitiveOrder(f.ident, w)
              case df: MDef => df.defType match {
                case DRecord => w.wl(s"tempResult = [self.${idObjc.field(f.ident)} compare:other.${idObjc.field(f.ident)}];")
                case DEnum => generatePrimitiveOrder(f.ident, w)
                case _ => throw new AssertionError("Unreachable")
              }
              case _ => throw new AssertionError("Unreachable")
            }
            w.w("if (tempResult != NSOrderedSame)").braced {
              w.wl("return tempResult;")
            }
          }
          w.wl("return NSOrderedSame;")
        }
      }
      w.wl
      w.wl("@end")
    })
  }

  def writeObjcFile(fileName: String, origin: String, refs: Iterable[String], f: IndentWriter => Unit) {
    createFile(spec.objcPrivateOutFolder.get, fileName, (w: IndentWriter) => {
      w.wl("// AUTOGENERATED FILE - DO NOT MODIFY!")
      w.wl("// This file generated by Djinni from " + origin)
      w.wl
      if (refs.nonEmpty) {
        // Ignore the ! in front of each line; used to put own headers to the top
        // according to Objective-C style guide
        refs.foreach(s => w.wl(if (s.charAt(0) == '!') s.substring(1) else s))
        w.wl
      }
      f(w)
    })
  }

  def copyObjcValue(to: String, from: String, ty: TypeRef, w: IndentWriter): Unit =
    copyObjcValue(to, from, ty.resolved, w)
  def copyObjcValue(to: String, from: String, tm: MExpr, w: IndentWriter): Unit = {
    def f(to: String, from: String, tm: MExpr, needRef: Boolean, w: IndentWriter, valueLevel: Int): Unit = {
      tm.base match {
        case MOptional => {
          w.wl(s"if ($from == nil) {").nested {
            w.wl(s"$to = nil;")
          }
          w.wl("} else {").nested {
            f(to, from, tm.args.head, true, w, valueLevel)
          }
          w.wl("}")
        }
        case p: MPrimitive => w.wl(s"$to = $from;") // NSNumber is immutable, so are primitive values
        case MString => w.wl(s"$to = [$from copy];")
        case MDate => w.wl(s"$to = [$from copy];")
        case MBinary => w.wl(s"$to = [$from copy];")
        case MList => {
          val copyName = "copiedValue_" + valueLevel
          val currentName = "currentValue_" + valueLevel
          w.wl(s"std::vector<${toObjcTypeDef(tm.args.head, true)}> ${to}TempVector;")
          w.wl(s"${to}TempVector.reserve([$from count]);")
          w.w(s"for (${toObjcTypeDef(tm.args.head, true)}$currentName in $from)").braced {
            w.wl(s"${toObjcTypeDef(tm.args.head, true)}$copyName;")
            f(copyName, currentName, tm.args.head, true, w, valueLevel + 1)
            w.wl(s"${to}TempVector.push_back($copyName);")
          }
          w.wl(s"$to = [NSArray arrayWithObjects:&${to}TempVector[0] count:${to}TempVector.size()];")
        }
        case MSet => {
          val copyName = "copiedValue_" + valueLevel
          val currentName = "currentValue_" + valueLevel
          w.wl(s"std::vector<${toObjcTypeDef(tm.args.head, true)}> ${to}TempVector;")
          w.wl(s"${to}TempVector.reserve([$from count]);")
          w.w(s"for (${toObjcTypeDef(tm.args.head, true)}$currentName in $from)").braced {
            w.wl(s"${toObjcTypeDef(tm.args.head, true)}$copyName;")
            f(copyName, currentName, tm.args.head, true, w, valueLevel + 1)
            w.wl(s"${to}TempVector.push_back($copyName);")
          }
          w.wl(s"$to = [NSSet setWithObjects:&${to}TempVector[0] count:${to}TempVector.size()];")
        }
        case MMap => {
          val keyType = toObjcTypeDef(tm.args.apply(0), true)
          val valueType = toObjcTypeDef(tm.args.apply(1), true)
          w.wl(s"std::vector<$keyType> ${to}TempKeyVector;")
          w.wl(s"${to}TempKeyVector.reserve([$from count]);")
          w.wl(s"std::vector<$valueType> ${to}TempValueVector;")
          w.wl(s"${to}TempValueVector.reserve([$from count]);")
          val keyName = "key_" + valueLevel
          val valueName = "value_" + valueLevel
          val copiedValueName = "copiedValue_" + valueLevel
          w.w(s"for ($keyType$keyName in $from)").braced {
            w.wl(s"$valueType$copiedValueName;")
            w.wl(s"${to}TempKeyVector.push_back($keyName);")
            w.wl(s"$valueType$valueName = [$from objectForKey:$keyName];")
            f(copiedValueName, valueName, tm.args.apply(1), true, w, valueLevel + 1)
            w.wl(s"${to}TempValueVector.push_back($copiedValueName);")
          }
          w.wl(s"$to = [NSDictionary dictionaryWithObjects:&${to}TempValueVector[0] forKeys:&${to}TempKeyVector[0] count:[$from count]];")
        }
        case d: MDef => {
          val typeName = d.name
          val self = idObjc.ty(typeName)
          d.defType match {
            case DEnum => w.wl(s"$to = $from;")
            case DRecord => w.wl(s"$to = [[${idObjc.ty(d.name)} alloc] ${idObjc.method("init_with_" + d.name)}:$from];")
            case DInterface => w.wl(s"$to = $from;")
          }
        }
        case p: MParam =>
      }
    }
    f(to, from, tm, false, w, 0)
  }

  def translateCppTypeToObjc(objcIdent: String, cppIdent: String, ty: TypeRef, withDecl: Boolean, w: IndentWriter): Unit =
    translateCppTypeToObjc(objcIdent, cppIdent, ty.resolved, withDecl, w)
  def translateCppTypeToObjc(objcIdent: String, cppIdent: String, tm: MExpr, withDecl: Boolean, w: IndentWriter): Unit = {
    def f(objcIdent: String, cppIdent: String, tm: MExpr, needRef: Boolean, withDecl: Boolean, w: IndentWriter, valueLevel: Int): Unit = {
      val objcType = if (withDecl) toObjcTypeDef(tm, needRef) else ""
      tm.base match {
        case MOptional =>
          // We use "nil" for the empty optional.
          assert(tm.args.size == 1)
          val arg = tm.args.head
          arg.base match {
            case MOptional => throw new AssertionError("nested optional?")
            case m =>
              if (withDecl) w.wl(s"$objcType$objcIdent;")
              w.wl(s"if ($cppIdent) {").nested {
                f(objcIdent, "(*(" + cppIdent + "))", arg, true, false, w, valueLevel)
              }
              w.wl("} else {").nested {
                m match {
                  case d: MDef if d.defType == DEnum => w.wl(s"$objcIdent = [NSNumber numberWithInt:-1];");
                  case _ => w.wl(s"$objcIdent = nil;")
                }
              }
              w.wl("}")
          }
        case o => o match {
          case p: MPrimitive =>
            val boxed = if(needRef) "::Boxed" else ""
            p.idlName match {
              case "i8" => w.wl(s"$objcType$objcIdent = ::djinni::I8$boxed::fromCpp($cppIdent);")
              case "i16" => w.wl(s"$objcType$objcIdent = ::djinni::I16$boxed::fromCpp($cppIdent);")
              case "i32" => w.wl(s"$objcType$objcIdent = ::djinni::I32$boxed::fromCpp($cppIdent);")
              case "i64" => w.wl(s"$objcType$objcIdent = ::djinni::I64$boxed::fromCpp($cppIdent);")
              case "f64" => w.wl(s"$objcType$objcIdent = ::djinni::F64$boxed::fromCpp($cppIdent);")
              case "bool" => w.wl(s"$objcType$objcIdent = ::djinni::Bool$boxed::fromCpp($cppIdent);")
            }
          case MString =>
            w.wl(s"$objcType$objcIdent = ::djinni::String::fromCpp($cppIdent);")
          case MDate => {
            w.wl(s"$objcType$objcIdent = [NSDate dateWithTimeIntervalSince1970:").nestedN(2) {
                w.wl(s"std::chrono::duration_cast<std::chrono::duration<double>>($cppIdent.time_since_epoch()).count()];")
            }
          }
          case MBinary =>
            w.wl(s"$objcType$objcIdent = ::djinni::Binary::fromCpp($cppIdent);")
          case MOptional => throw new AssertionError("optional should have been special cased")
          case MList =>
            val objcName = "objcValue_" + valueLevel
            val cppName = "cppValue_" + valueLevel
            w.wl(s"std::vector<${toObjcTypeDef(tm.args.head, true)}> ${objcIdent}TempVector;")
            w.wl(s"${objcIdent}TempVector.reserve(${cppIdent}.size());")
            w.w(s"for (const auto & $cppName : ${cppIdent})")
            w.braced {
              f(objcName, cppName, tm.args.head, true, true, w, valueLevel + 1)
              w.wl(s"${objcIdent}TempVector.push_back($objcName);")
            }
            w.wl(s"$objcType$objcIdent = [NSArray arrayWithObjects:&${objcIdent}TempVector[0] count:${objcIdent}TempVector.size()];")
          case MSet =>
            val objcName = "objcValue_" + valueLevel
            val cppName = "cppValue_" + valueLevel
            w.wl(s"std::vector<${toObjcTypeDef(tm.args.head, true)}> ${objcIdent}TempVector;")
            w.wl(s"${objcIdent}TempVector.reserve(${cppIdent}.size());")
            w.w(s"for (const auto & $cppName : ${cppIdent})")
            w.braced {
              f(objcName, cppName, tm.args.head, true, true, w, valueLevel + 1)
              w.wl(s"${objcIdent}TempVector.push_back($objcName);")
            }
            w.wl(s"$objcType$objcIdent = [NSSet setWithObjects:&${objcIdent}TempVector[0] count:${objcIdent}TempVector.size()];")
          case MMap => {
            val cppPairName = "cppPair_" + valueLevel
            val objcKeyName = "objcKey_" + valueLevel
            val objcValueName = "objcValue_" + valueLevel
            w.wl(s"std::vector<${toObjcTypeDef(tm.args.apply(0), true)}> ${objcIdent}TempKeyVector;")
            w.wl(s"${objcIdent}TempKeyVector.reserve(${cppIdent}.size());")
            w.wl(s"std::vector<${toObjcTypeDef(tm.args.apply(1), true)}> ${objcIdent}TempValueVector;")
            w.wl(s"${objcIdent}TempValueVector.reserve(${cppIdent}.size());")
            w.w(s"for (const auto & $cppPairName : ${cppIdent})").braced {
              f(objcKeyName, cppPairName + ".first", tm.args.apply(0), true, true, w, valueLevel + 1)
              f(objcValueName, cppPairName + ".second", tm.args.apply(1), true, true, w, valueLevel + 1)
              w.wl(s"${objcIdent}TempKeyVector.push_back($objcKeyName);")
              w.wl(s"${objcIdent}TempValueVector.push_back($objcValueName);")
            }
            w.wl(s"$objcType$objcIdent = [NSDictionary dictionaryWithObjects:&${objcIdent}TempValueVector[0] forKeys:&${objcIdent}TempKeyVector[0] count:${cppIdent}.size()];")
          }
          case d: MDef => {
            val typeName = d.name
            val self = idObjc.ty(typeName)
            d.defType match {
              case DEnum =>
                val cppSelf = cppMarshal.fqTypename(tm)
                if (needRef)
                  w.wl(s"$objcType$objcIdent = ::djinni::Enum<$cppSelf, $self>::Boxed::fromCpp($cppIdent);")
                else
                  w.wl(s"$objcType$objcIdent = ::djinni::Enum<$cppSelf, $self>::fromCpp($cppIdent);")
              case DRecord => w.wl(s"$objcType$objcIdent = [[${self} alloc] initWithCpp${IdentStyle.camelUpper(typeName)}:$cppIdent];")
              case DInterface =>
                val ext = d.body.asInstanceOf[Interface].ext
                val objcProxy = objcppMarshal.fqHelperClass(d.name + "_objc_proxy")
                val helperClass = objcppMarshal.fqHelperClass(typeName)
                (ext.cpp, ext.objc) match {
                  case (true, true) => throw new AssertionError("Function implemented on both sides")
                  case (false, false) => throw new AssertionError("Function not implemented")
                  case (true, false) => w.wl(s"$objcType$objcIdent = $helperClass::fromCpp($cppIdent);")
                  case (false, true) => w.wl(s"$objcType$objcIdent = $helperClass::fromCpp($cppIdent);")
                }
            }
          }
          case p: MParam =>
        }
      }
    }
    f(objcIdent, cppIdent, tm, false, withDecl, w, 0)
  }

  def translateObjcTypeToCpp(cppIdent: String, objcIdent: String, ty: TypeRef, w: IndentWriter): Unit =
    translateObjcTypeToCpp(cppIdent, objcIdent, ty.resolved, w)
  def translateObjcTypeToCpp(cppIdent: String, objcIdent: String, tm: MExpr, w: IndentWriter): Unit = {
    def f(cppIdent: String, objcIdent: String, tm: MExpr, needRef: Boolean, w: IndentWriter, valueLevel: Int): Unit = {
      val cppType = cppMarshal.fqTypename(tm)
      tm.base match {
        case MOptional =>
          // We use "nil" for the empty optional.
          assert(tm.args.size == 1)
          val arg = tm.args.head
          arg.base match {
            case MOptional => throw new AssertionError("nested optional?")
            case m =>
              w.wl(s"$cppType $cppIdent;")
              m match {
                case d: MDef if d.defType == DEnum =>
                  val enumVal = if (needRef) objcIdent else s"static_cast<${idObjc.ty(d.name)}>([$objcIdent intValue])"
                  w.w(s"if ($enumVal != static_cast<${idObjc.ty(d.name)}>(-1))")
                case _ => w.w(s"if ($objcIdent != nil)")
              }
              w.braced {
                f("optValue", objcIdent, arg, true, w, 0)
                w.wl(s"$cppIdent = optValue;")
              }
          }
        case o => o match {
          case p: MPrimitive =>
            val boxed = if(needRef) "::Boxed" else ""
            p.idlName match {
              case "i8" => w.wl(s"$cppType $cppIdent = ::djinni::I8$boxed::toCpp($objcIdent);")
              case "i16" => w.wl(s"$cppType $cppIdent = ::djinni::I16$boxed::toCpp($objcIdent);")
              case "i32" => w.wl(s"$cppType $cppIdent = ::djinni::I32$boxed::toCpp($objcIdent);")
              case "i64" => w.wl(s"$cppType $cppIdent = ::djinni::I64$boxed::toCpp($objcIdent);")
              case "f64" => w.wl(s"$cppType $cppIdent = ::djinni::F64$boxed::toCpp($objcIdent);")
              case "bool" => w.wl(s"$cppType $cppIdent = ::djinni::Bool$boxed::toCpp($objcIdent);")
            }
          case MString =>
            w.wl(s"$cppType $cppIdent = ::djinni::String::toCpp($objcIdent);")
          case MDate => w.wl(s"$cppType $cppIdent = ::djinni::convert_date([$objcIdent timeIntervalSince1970]);")
          case MBinary =>
            w.wl(s"$cppType $cppIdent = ::djinni::Binary::toCpp($objcIdent);")
          case MOptional => throw new AssertionError("optional should have been special cased")
          case MList =>
            val cppName = "cppValue_" + valueLevel
            val objcName = "objcValue_" + valueLevel
            w.wl(s"$cppType $cppIdent;")
            w.wl(s"$cppIdent.reserve([$objcIdent count]);")
            w.w(s"for (${toObjcTypeDef(tm.args.head, true)}$objcName in $objcIdent)").braced {
              f(cppName, objcName, tm.args.head, true, w, valueLevel + 1)
              w.wl(s"$cppIdent.push_back(std::move($cppName));")
            }
          case MSet =>
            val cppName = "cppValue_" + valueLevel
            val objcName = "objcValue_" + valueLevel
            w.wl(s"$cppType $cppIdent;")
            w.w(s"for (${toObjcTypeDef(tm.args.head, true)}$objcName in $objcIdent)").braced {
              f(cppName, objcName, tm.args.head, true, w, valueLevel + 1)
              w.wl(s"$cppIdent.insert(std::move($cppName));")
            }
          case MMap =>
            val objcKeyName = "objcKey_" + valueLevel
            val cppKeyName = "cppKey_" + valueLevel
            val cppValueName = "cppValue_" + valueLevel
            w.wl(s"$cppType $cppIdent;")
            w.w(s"for (id $objcKeyName in $objcIdent)").braced {
              f(cppKeyName, objcKeyName, tm.args.apply(0), true, w, valueLevel + 1)
              f(cppValueName, s"[$objcIdent objectForKey:$objcKeyName]", tm.args.apply(1), true, w, valueLevel + 1)
              w.wl(s"$cppIdent.emplace(std::move($cppKeyName), std::move($cppValueName));")
            }
          case d: MDef => {
            val typeName = d.name
            val self = idObjc.ty(typeName)
            d.defType match {
              case DEnum =>
                val cppSelf = cppMarshal.fqTypename(tm)
                if(needRef)
                  w.wl(s"$cppType $cppIdent = ::djinni::Enum<$cppSelf, $self>::Boxed::toCpp($objcIdent);")
                else
                  w.wl(s"$cppType $cppIdent = ::djinni::Enum<$cppSelf, $self>::toCpp($objcIdent);")
              case DRecord => w.wl(s"$cppType $cppIdent = std::move([$objcIdent cpp${IdentStyle.camelUpper(typeName)}]);")
              case DInterface =>
                val ext = d.body.asInstanceOf[Interface].ext
                val helperClass = objcppMarshal.fqHelperClass(typeName)
                (ext.cpp, ext.objc) match {
                  case (true, true) => throw new AssertionError("Function implemented on both sides")
                  case (false, false) => throw new AssertionError("Function not implemented")
                  case (true, false) => w.wl(s"$cppType $cppIdent = $helperClass::toCpp($objcIdent);")
                  case (false, true) => w.wl(s"$cppType $cppIdent = $helperClass::toCpp($objcIdent);")
                }
            }
          }
          case p: MParam =>
        }
      }
    }
    f(cppIdent, objcIdent, tm, false, w, 0)
  }

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
                if (ext.cpp) (s"${idObjc.ty(d.name)}*", false) else (s"id<${idObjc.ty(d.name)}>", false)
            }
            case p: MParam => throw new AssertionError("Parameter should not happen at Obj-C top level")
          }
          base
      }
    }
    f(tm, needRef)
  }

  def toObjcTypeDef(ty: TypeRef): String = toObjcTypeDef(ty.resolved, false)
  def toObjcTypeDef(ty: TypeRef, needRef: Boolean): String = toObjcTypeDef(ty.resolved, needRef)
  def toObjcTypeDef(tm: MExpr): String = toObjcTypeDef(tm, false)
  def toObjcTypeDef(tm: MExpr, needRef: Boolean): String = {
    val (name, asterisk) = toObjcType(tm, needRef)
    name + (if (asterisk) " *" else " ")
  }

}