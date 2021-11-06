package com.btcontract.wallet.sheets

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import android.os.Bundle
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{ImageButton, TextView}
import androidx.appcompat.view.ContextThemeWrapper
import com.btcontract.wallet.{BaseActivity, R, WalletApp}
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.journeyapps.barcodescanner.{BarcodeCallback, BarcodeResult, BarcodeView}
import com.sparrowwallet.hummingbird.{ResultType, UR, URDecoder}
import fr.acinq.bitcoin.DeterministicWallet._
import immortan.crypto.Tools._
import immortan.utils.ImplicitJsonFormats._
import immortan.utils.InputParser
import spray.json._

import scala.util.{Failure, Success, Try}


trait HasBarcodeReader extends BarcodeCallback {
  var lastAttempt: Long = System.currentTimeMillis
  var barcodeReader: BarcodeView = _
  var instruction: TextView = _
}

trait HasUrDecoder extends HasBarcodeReader {
  val decoder: URDecoder = new URDecoder
  def onError(error: String)
  def onUR(ur: UR): Unit

  def handleUR(part: String): Unit = {
    val isUseful = decoder.receivePart(part)
    val percent = decoder.getEstimatedPercentComplete

    if (!isUseful && System.currentTimeMillis - lastAttempt > 2000) {
      WalletApp.app.quickToast(R.string.error_nothing_useful)
      lastAttempt = System.currentTimeMillis
    }

    if (percent > 0D) {
      val pct = (percent * 100).floor.toLong
      instruction.setText(s"$pct%")
    }

    for {
      result <- Option(decoder.getResult)
      isOK = result.resultType == ResultType.SUCCESS
    } if (isOK) onUR(result.ur) else onError(result.error)
  }
}

abstract class ScannerBottomSheet(host: BaseActivity) extends BottomSheetDialogFragment with HasBarcodeReader {
  def resumeBarcodeReader: Unit = runAnd(barcodeReader decodeContinuous this)(barcodeReader.resume)
  def pauseBarcodeReader: Unit = runAnd(barcodeReader setTorch false)(barcodeReader.pause)

  override def onDestroy: Unit = runAnd(barcodeReader.stopDecoding)(super.onStop)
  override def onResume: Unit = runAnd(resumeBarcodeReader)(super.onResume)
  override def onStop: Unit = runAnd(pauseBarcodeReader)(super.onStop)
  var flashlight: ImageButton = _

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, state: Bundle): View = {
    val contextThemeWrapper = new ContextThemeWrapper(host, R.style.AppTheme)
    val inflatorExt = inflater.cloneInContext(contextThemeWrapper)
    inflatorExt.inflate(R.layout.sheet_scanner, container, false)
  }

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    instruction = view.findViewById(R.id.instruction).asInstanceOf[TextView]
    barcodeReader = view.findViewById(R.id.reader).asInstanceOf[BarcodeView]
    flashlight = view.findViewById(R.id.flashlight).asInstanceOf[ImageButton]
    flashlight setOnClickListener host.onButtonTap(toggleTorch)
  }

  def toggleTorch: Unit = {
    val currentTag = flashlight.getTag.asInstanceOf[Int]

    if (currentTag != R.drawable.flashlight_on) {
      flashlight.setImageResource(R.drawable.flashlight_on)
      flashlight.setTag(R.drawable.flashlight_on)
      barcodeReader.setTorch(true)
    } else {
      flashlight.setImageResource(R.drawable.flashlight_off)
      flashlight.setTag(R.drawable.flashlight_off)
      barcodeReader.setTorch(false)
    }
  }
}

class OnceBottomSheet(host: BaseActivity, instructionOpt: Option[String], onScan: Runnable) extends ScannerBottomSheet(host) {
  def failedScan(error: Throwable): Unit = WalletApp.app.quickToast(error.getMessage)
  def successfulScan(result: Any): Unit = runAnd(dismiss)(onScan.run)

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    super.onViewCreated(view, savedState)

    instructionOpt foreach { instructionText =>
      host.setVis(isVisible = true, instruction)
      instruction.setText(instructionText)
    }
  }

  override def barcodeResult(scanningResult: BarcodeResult): Unit = for {
    text <- Option(scanningResult.getText) if System.currentTimeMillis - lastAttempt > 2000
    _ = host.runInFutureProcessOnUI(InputParser.recordValue(text), failedScan)(successfulScan)
  } lastAttempt = System.currentTimeMillis
}

class URBottomSheet(host: BaseActivity, onKey: ExtendedPublicKey => Unit) extends ScannerBottomSheet(host) with HasUrDecoder { me =>
  private[this] val bip84PathPrefix = KeyPath(hardened(84L) :: hardened(0L) :: Nil)
  private[this] val zPubPrefix = "zpub"

  override def onViewCreated(view: View, savedState: Bundle): Unit = {
    super.onViewCreated(view, savedState)

    val tip = host.getString(R.string.settings_hw_zpub_tip)
    host.setVis(isVisible = true, instruction)
    instruction.setText(tip)
  }

  override def barcodeResult(res: BarcodeResult): Unit = {
    val isZPub = res.getText.toLowerCase.startsWith(zPubPrefix)
    if (isZPub) handleZpub(res.getText) else handleUR(res.getText)
  }

  def decodeZPubFromString(zPub: String): (Int, ExtendedPublicKey) =
    ExtendedPublicKey.decode(zPub, bip84PathPrefix)

  def handleZpub(zPub: String): Unit = {
    Try(me decodeZPubFromString zPub) match {
      case Success(_ ~ extendedPubKey) => onKey(extendedPubKey)
      case Failure(exception) => host.onFail(exception)
    }

    dismiss
  }

  override def onError(error: String): Unit = {
    host.onFail(error)
    dismiss
  }

  override def onUR(ur: UR): Unit = Try {
    val urBytes = ur.decodeFromRegistry.asInstanceOf[Bytes]
    val charBuffer = StandardCharsets.UTF_8.newDecoder.decode(ByteBuffer wrap urBytes).toString
    val bip84zPub = charBuffer.parseJson.asJsObject.fields("bip84").asJsObject.fields("xpub")
    json2String(bip84zPub)
  } match {
    case Success(zPubString) => handleZpub(zPubString)
    case Failure(why) => onError(why.getMessage)
  }
}
