package piuk.blockchain.android.data.datamanagers;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import piuk.blockchain.android.ui.zxing.Contents;
import piuk.blockchain.android.ui.zxing.encode.QRCodeEncoder;

import android.graphics.Bitmap;

import piuk.blockchain.android.data.rxjava.RxUtil;
import rx.Observable;

public class ReceiveDataManager {

    /**
     * Generates a QR code in Bitmap format from a given URI to specified dimensions, wrapped in an
     * Observable. Will throw an error if the Bitmap is null.
     *
     * @param uri        A string to be encoded
     * @param dimensions The dimensions of the QR code to be returned
     * @return An Observable wrapping the generate Bitmap operation
     */
    public Observable<Bitmap> generateQrCode(String uri, int dimensions) {
        return generateQrCodeObservable(uri, dimensions)
                .compose(RxUtil.applySchedulers());
    }

    private Observable<Bitmap> generateQrCodeObservable(String uri, int dimensions) {
        return Observable.defer(() -> {
            Bitmap bitmap = null;
            QRCodeEncoder qrCodeEncoder = new QRCodeEncoder(uri, null, Contents.Type.TEXT, BarcodeFormat.QR_CODE.toString(), dimensions);
            try {
                bitmap = qrCodeEncoder.encodeAsBitmap();
            } catch (WriterException e) {
                return Observable.error(e);
            }

            if (bitmap == null) {
                return Observable.error(new Throwable("Bitmap was null"));
            } else {
                return Observable.just(bitmap);
            }
        });
    }

}
