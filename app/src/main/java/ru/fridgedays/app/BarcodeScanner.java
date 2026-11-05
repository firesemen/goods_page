package ru.fridgedays.app;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;

final class BarcodeScanner {
    static Result decode(byte[] frame,int width,int height,boolean product)throws NotFoundException{
        MultiFormatReader reader=new MultiFormatReader();reader.setHints(Map.of(DecodeHintType.POSSIBLE_FORMATS,product?List.of(BarcodeFormat.EAN_13,BarcodeFormat.EAN_8,BarcodeFormat.UPC_A,BarcodeFormat.QR_CODE,BarcodeFormat.DATA_MATRIX,BarcodeFormat.CODE_128):List.of(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER,Boolean.TRUE));
        byte[] y=frame;
        for(int rotation=0;rotation<4;rotation++){
            try{return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(y,width,height,0,0,width,height,false))));}
            catch(NotFoundException e){byte[] rotated=new byte[width*height];for(int row=0;row<height;row++)for(int col=0;col<width;col++)rotated[col*height+height-row-1]=y[row*width+col];y=rotated;int swap=width;width=height;height=swap;}finally{reader.reset();}
        }throw NotFoundException.getNotFoundInstance();
    }
}
