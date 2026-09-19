package com.meshdrop.transfer.client
import com.meshdrop.core.model.TransferFile
import java.io.InputStream
fun interface PayloadSource { fun open(file: TransferFile): Pair<InputStream, Long>? }
