/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.phoenix.utils

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.google.common.base.Strings
import com.google.common.net.HostAndPort
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.*
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.phoenix.BuildConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scala.Option
import java.io.File
import java.util.*

object Wallet {

  val log: Logger = LoggerFactory.getLogger(this::class.java)

  // ------------------------ DATADIR & FILES

  private const val ECLAIR_BASE_DATADIR = "node-data"
  private const val SEED_FILE = "seed.dat"
  private const val ECLAIR_DB_FILE = "eclair.sqlite"
  private const val NETWORK_DB_FILE = "network.sqlite"
  private const val WALLET_DB_FILE = "wallet.sqlite"
  internal const val DEFAULT_PIN = "111111"

  fun getDatadir(context: Context): File {
    return File(context.filesDir, ECLAIR_BASE_DATADIR)
  }

  fun getSeedFile(context: Context): File {
    return File(getDatadir(context), SEED_FILE)
  }

  fun getChainDatadir(context: Context): File {
    return File(getDatadir(context), BuildConfig.CHAIN)
  }

  fun getEclairDBFile(context: Context): File {
    return File(getChainDatadir(context), ECLAIR_DB_FILE)
  }

  fun getNetworkDBFile(context: Context): File {
    return File(getChainDatadir(context), NETWORK_DB_FILE)
  }

  fun getChainHash(): ByteVector32 {
    return if ("mainnet" == BuildConfig.CHAIN) Block.LivenetGenesisBlock().hash() else Block.TestnetGenesisBlock().hash()
  }

  fun getNodeKeyPath(): DeterministicWallet.KeyPath {
    return if ("mainnet" == BuildConfig.CHAIN) DeterministicWallet.`KeyPath$`.`MODULE$`.apply("m/84'/0'/0'/0/0") else DeterministicWallet.`KeyPath$`.`MODULE$`.apply("m/84'/1'/0'/0/0")
  }

  fun cleanInvoice(input: String): String {
    val trimmed = input.replace("\\u00A0", "").trim()
    return when {
      trimmed.startsWith("lightning://", true) -> trimmed.drop(12)
      trimmed.startsWith("lightning:", true) -> trimmed.drop(10)
      trimmed.startsWith("bitcoin://", true) -> trimmed.drop(10)
      trimmed.startsWith("bitcoin:", true) -> trimmed.drop(8)
      else -> trimmed
    }
  }

  fun extractInvoice(input: String): Any {
    val invoice = cleanInvoice(input)
    return try {
      PaymentRequest.read(invoice)
    } catch (e1: Exception) {
      try {
        BitcoinURI(invoice)
      } catch (e2: Exception) {
        try {
          LNUrl(input)
        } catch (e3: Exception) {
          log.debug("unhandled input=$input")
          log.debug("invalid as PaymentRequest: ${e1.localizedMessage}")
          log.debug("invalid as BitcoinURI: ${e2.localizedMessage}")
          log.debug("invalid as LNURL: ${e3.localizedMessage}")
          throw RuntimeException("not a valid invoice: ${e1.localizedMessage} / ${e2.localizedMessage} / ${e3.localizedMessage}")
        }
      }
    }
  }

  /**
   * Returns a Pair object containing the trampoline data for a payment request, only if it is necessary.
   * If trampoline is not needed, the node will be None, and the fee will be 0 msat.
   *
   * @return Left: optional trampoline node, Right: trampoline fee.
   */
  fun getTrampoline(amount: MilliSatoshi, paymentRequest: PaymentRequest): Pair<Option<Crypto.PublicKey>, MilliSatoshi> {
    val routingHeadShortChannelId = if (paymentRequest.routingInfo().headOption().isDefined && paymentRequest.routingInfo().head().headOption().isDefined)
      Option.apply(paymentRequest.routingInfo().head().head().shortChannelId()) else Option.empty()

    val trampolineNode: Option<Crypto.PublicKey> = when {
      // payment target is ACINQ: no trampoline
      paymentRequest.nodeId() == ACINQ.nodeId() -> Option.empty()
      // routing info head is a peer id: target is a phoenix app
      // routingHeadShortChannelId.isDefined && ShortChannelId.isPeerId(routingHeadShortChannelId.get()) -> Option.empty()
      // otherwise, we use ACINQ node for trampoline
      else -> Option.apply(ACINQ.nodeId())
    }

    val trampolineFee: MilliSatoshi = if (trampolineNode.isDefined) {
      Converter.any2Msat(Satoshi(5)).`$times`(5) // base fee covering 5 hops with a base fee of 5 sat
        .`$plus`(amount.`$times`(0.001))  // + proportional fee = 0.1 % of payment amount
    } else {
      MilliSatoshi(0) // no fee when we are not using trampoline
    }

    return Pair(trampolineNode, trampolineFee)
  }

  fun showKeyboard(context: Context?, view: View) {
    context?.let {
      val imm = it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.showSoftInput(view, InputMethodManager.RESULT_UNCHANGED_SHOWN)
    }
  }

  fun hideKeyboard(context: Context?, view: View) {
    context?.let {
      val imm = it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
  }

  /**
   * Builds a TypeSafe configuration for the node. Returns an empty config if there are no valid user's prefs.
   */
  fun getOverrideConfig(context: Context): Config {
    val electrumServer = Prefs.getElectrumServer(context)
    if (!Strings.isNullOrEmpty(electrumServer)) {
      try {
        val address = HostAndPort.fromString(electrumServer).withDefaultPort(50002)
        val conf = HashMap<String, Any>()
        if (!Strings.isNullOrEmpty(address.host)) {
          conf["eclair.electrum.host"] = address.host
          conf["eclair.electrum.port"] = address.port
          // custom server certificate must be valid
          conf["eclair.electrum.ssl"] = "strict"
          return ConfigFactory.parseMap(conf)
        }
      } catch (e: Exception) {
        log.error("invalid electrum server=$electrumServer, using empty config instead", e)
      }
    }
    return ConfigFactory.empty()
  }

  // ------------------------ NODES & API URLS

  const val PRICE_RATE_API = "https://blockchain.info/ticker"
  val ACINQ: NodeURI = NodeURI.parse("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@34.250.234.192:9735")
  const val WALLET_CONTEXT_SOURCE = "https://acinq.co/mobile/walletcontext.json"
  const val DEFAULT_ONCHAIN_EXPLORER = "https://api.blockcypher.com/v1/btc/test3/txs/"
}
