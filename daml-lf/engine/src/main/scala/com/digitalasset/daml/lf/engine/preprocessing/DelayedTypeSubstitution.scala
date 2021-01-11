// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package engine
package preprocessing

import com.daml.lf.language.Ast.{TVar, Type}
import data.Ref

// To handle a closed substitution in a lazy way while traversing a term top down.
private[preprocessing] class DelayedTypeSubstitution private(
    mapping: Map[Ref.Name, (DelayedTypeSubstitution, Type)]
) {

  // substitute typ if type is a variable
  // always return a non variable type
  @throws[IllegalArgumentException]
  def apply(typ: Type): (DelayedTypeSubstitution, Type) =
    typ match {
      case TVar(name) =>
        mapping.getOrElse(name, throw new IllegalArgumentException(s"unexpected free variable $name"))
      case otherwise => (this -> otherwise)
    }

  // variables that appear in `typ` must be in the domain of `mapping`
  def introVar(name: Ref.Name, typ: Type) =
    new DelayedTypeSubstitution(mapping.updated(name, apply(typ)))

  // variables that appear in `typ` in the domain of `mapping`
  // requirement: names.size == xs.size
  def introVars(newMapping: Iterable[(Ref.Name, Type)]): DelayedTypeSubstitution = {
    if (newMapping.isEmpty) {
      this
    } else {
      val updatedMapping = newMapping.foldLeft(mapping){
        case (acc, (name, typ)) => acc.updated(name, apply(typ))
      }
      new DelayedTypeSubstitution(updatedMapping)
    }
  }
}

object DelayedTypeSubstitution {
 val Empty = new DelayedTypeSubstitution(Map.empty)
}