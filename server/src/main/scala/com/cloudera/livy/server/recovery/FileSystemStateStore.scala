/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.livy.server.recovery

import java.io.IOException
import java.net.URI
import java.util
import java.util.UUID

import scala.reflect.ClassTag

import org.apache.commons.io.IOUtils
import org.apache.hadoop.fs._
import org.apache.hadoop.fs.Options.{CreateOpts, Rename}
import org.apache.hadoop.fs.permission.{FsAction, FsPermission}

import com.cloudera.livy.{LivyConf, Logging}
import com.cloudera.livy.Utils.usingResource

object FileSystemStateStore extends StateStoreCompanion {
  override def create(livyConf: LivyConf): StateStore = new FileSystemStateStore(livyConf)
}

class FileSystemStateStore(livyConf: LivyConf) extends StateStore with Logging {
  private val fsUri = {
    val fsPath = livyConf.get(LivyConf.RECOVERY_STATE_STORE_URL_CONF)
    require(!fsPath.isEmpty, s"Please config ${LivyConf.RECOVERY_STATE_STORE_URL_CONF.key}.")
    new URI(fsPath)
  }

  private val fileContext: FileContext = FileContext.getFileContext(fsUri)

  {
    // Only Livy user should have access to state files.
    fileContext.setUMask(new FsPermission("077"))

    // Create state store dir if it doesn't exist.
    try {
      fileContext.mkdir(absPath("."), FsPermission.getDirDefault(), true)
    } catch {
      case _: FileAlreadyExistsException =>
    }

    // Check permission of state store dir.
    val fileStatus = fileContext.getFileStatus(absPath("."))
    assert(fileStatus.getPermission.getUserAction() == FsAction.ALL,
      s"Livy doesn't have permission to access state store: $fsUri.")
    assert(fileStatus.getPermission.getGroupAction() == FsAction.NONE,
      s"Group users have permission to access state store: $fsUri. This is insecure.")
    assert(fileStatus.getPermission.getOtherAction() == FsAction.NONE,
      s"Other users have permission to access state store: $fsUri. This is insecure.")
  }

  override def set(key: String, value: Object): Unit = {
    // Write to a temp file then rename to avoid file corruption if livy-server crashes
    // in the middle of the write.
    val tmpPath = absPath(s"$key.tmp")
    val createFlag = util.EnumSet.of(CreateFlag.CREATE, CreateFlag.OVERWRITE)

    usingResource(fileContext.create(tmpPath, createFlag, CreateOpts.createParent())) { tmpFile =>
      tmpFile.write(serializeToBytes(value))
      tmpFile.close()
      // Assume rename is atomic.
      fileContext.rename(tmpPath, absPath(key), Rename.OVERWRITE)
    }
  }

  override def get[T: ClassTag](key: String): Option[T] = {
    try {
      usingResource(fileContext.open(absPath(key))) { is =>
        Option(deserialize[T](IOUtils.toByteArray(is)))
      }
    } catch {
      case _: IOException => None
    }
  }

  override def getChildren(key: String): Seq[String] = {
    try {
      fileContext.util.listStatus(absPath(key)).map(_.getPath.getName)
    } catch {
      case _: IOException => Seq.empty
    }
  }

  override def remove(key: String): Unit = {
    fileContext.delete(absPath(key), false)
  }

  private def absPath(key: String): Path = new Path(fsUri.getPath(), key)
}
