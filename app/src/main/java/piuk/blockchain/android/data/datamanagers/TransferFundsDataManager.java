package piuk.blockchain.android.data.datamanagers;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.Pair;

import info.blockchain.api.Unspent;
import info.blockchain.wallet.multiaddr.MultiAddrFactory;
import info.blockchain.wallet.payload.LegacyAddress;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.payment.Payment;
import info.blockchain.wallet.payment.data.SweepBundle;
import info.blockchain.wallet.payment.data.UnspentOutputs;
import info.blockchain.wallet.send.SendCoins;
import info.blockchain.wallet.util.CharSequenceX;

import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import piuk.blockchain.android.data.cache.DynamicFeeCache;
import piuk.blockchain.android.data.rxjava.RxUtil;
import piuk.blockchain.android.ui.account.ItemAccount;
import piuk.blockchain.android.ui.send.PendingTransaction;
import rx.Observable;

public class TransferFundsDataManager {

    private PayloadManager mPayloadManager;
    private Unspent mUnspentApi;
    private Payment mPayment;

    public TransferFundsDataManager(PayloadManager payloadManager, Unspent unspentApi, Payment payment) {
        mPayloadManager = payloadManager;
        mUnspentApi = unspentApi;
        mPayment = payment;
    }

    /**
     * Check if there are any spendable legacy funds that need to be sent to a HD wallet. Constructs
     * a list of {@link PendingTransaction} objects with outputs set to an account defined by it's
     * index in the list of HD accounts.
     *
     * @param addressToReceiveIndex The index of the account to which you want to send the funds
     * @return Returns a Map which bundles together the List of {@link PendingTransaction} objects,
     * as well as a Pair which contains the total to send and the total fees, in that order.
     */
    public Observable<Map<List<PendingTransaction>, Pair<Long, Long>>> getTransferableFundTransactionList(int addressToReceiveIndex) {
        return Observable.fromCallable(() -> {
                    BigInteger suggestedFeePerKb = DynamicFeeCache.getInstance().getSuggestedFee().defaultFeePerKb;
                    List<PendingTransaction> pendingTransactionList = new ArrayList<>();
                    List<LegacyAddress> legacyAddresses = mPayloadManager.getPayload().getLegacyAddresses();

                    long totalToSend = 0L;
                    long totalFee = 0L;

                    for (LegacyAddress legacyAddress : legacyAddresses) {

                        if (!legacyAddress.isWatchOnly() && MultiAddrFactory.getInstance().getLegacyBalance(legacyAddress.getAddress()) > 0) {
                            JSONObject unspentResponse = mUnspentApi.getUnspentOutputs(legacyAddress.getAddress());
                            if (unspentResponse != null) {
                                UnspentOutputs coins = mPayment.getCoins(unspentResponse);
                                SweepBundle sweepBundle = mPayment.getSweepBundle(coins, suggestedFeePerKb);

                                // Don't sweep if there are still unconfirmed funds in address
                                if (coins.getNotice() == null && sweepBundle.getSweepAmount().compareTo(SendCoins.bDust) == 1) {
                                    PendingTransaction pendingSpend = new PendingTransaction();
                                    pendingSpend.unspentOutputBundle = mPayment.getSpendableCoins(coins, sweepBundle.getSweepAmount(), suggestedFeePerKb);
                                    pendingSpend.sendingObject = new ItemAccount(legacyAddress.getLabel(), "", "", legacyAddress);
                                    pendingSpend.bigIntFee = pendingSpend.unspentOutputBundle.getAbsoluteFee();
                                    pendingSpend.bigIntAmount = sweepBundle.getSweepAmount();
                                    pendingSpend.addressToReceiveIndex = addressToReceiveIndex;
                                    totalToSend += pendingSpend.bigIntAmount.longValue();
                                    totalFee += pendingSpend.bigIntFee.longValue();
                                    pendingTransactionList.add(pendingSpend);
                                }
                            }
                        }
                    }

                    Map<List<PendingTransaction>, Pair<Long, Long>> map = new HashMap<>();
                    map.put(pendingTransactionList, new Pair<>(totalToSend, totalFee));
                    return map;
                }
        ).compose(RxUtil.applySchedulers());
    }

    /**
     * Check if there are any spendable legacy funds that need to be sent to a HD wallet. Constructs
     * a list of {@link PendingTransaction} objects with outputs set to the default HD account.
     *
     * @return Returns a Map which bundles together the List of {@link PendingTransaction} objects,
     * as well as a Pair which contains the total to send and the total fees, in that order.
     */
    public Observable<Map<List<PendingTransaction>, Pair<Long, Long>>> getTransferableFundTransactionListForDefaultAccount() {
        return getTransferableFundTransactionList(mPayloadManager.getPayload().getHdWallet().getDefaultIndex());
    }

    /**
     * Takes a list of {@link PendingTransaction} objects and transfers them all. Emits a String
     * which is the Tx hash for each successful payment, and calls onCompleted when all
     * PendingTransactions have been finished successfully.
     *
     * @param payment             A new {@link Payment} object
     * @param pendingTransactions A list of {@link PendingTransaction} objects
     * @param secondPassword      The double encryption password if necessary
     * @return An {@link Observable<String>}
     */
    public Observable<String> sendPayment(@NonNull Payment payment,
                                          @NonNull List<PendingTransaction> pendingTransactions,
                                          @Nullable CharSequenceX secondPassword) {
        return getPaymentObservable(payment, pendingTransactions, secondPassword)
                .compose(RxUtil.applySchedulers());
    }

    private Observable<String> getPaymentObservable(Payment payment, List<PendingTransaction> pendingTransactions, CharSequenceX secondPassword) {
        return Observable.create(subscriber -> {
            for (int i = 0; i < pendingTransactions.size(); i++) {
                PendingTransaction pendingTransaction = pendingTransactions.get(i);

                boolean isWatchOnly = false;

                LegacyAddress legacyAddress = ((LegacyAddress) pendingTransaction.sendingObject.accountObject);
                String changeAddress = legacyAddress.getAddress();
                String receivingAddress = mPayloadManager.getReceiveAddress(pendingTransaction.addressToReceiveIndex);

                isWatchOnly = legacyAddress.isWatchOnly();

                final int finalI = i;
                try {
                    payment.submitPayment(
                            pendingTransaction.unspentOutputBundle,
                            null,
                            legacyAddress,
                            receivingAddress,
                            changeAddress,
                            pendingTransaction.note,
                            pendingTransaction.bigIntFee,
                            pendingTransaction.bigIntAmount,
                            isWatchOnly,
                            secondPassword != null ? secondPassword.toString() : null,
                            new Payment.SubmitPaymentListener() {
                                @Override
                                public void onSuccess(String s) {
                                    subscriber.onNext(s);
                                    MultiAddrFactory.getInstance().setLegacyBalance(MultiAddrFactory.getInstance().getLegacyBalance() - (pendingTransaction.bigIntAmount.longValue() + pendingTransaction.bigIntFee.longValue()));

                                    if (finalI == pendingTransactions.size() - 1) {
                                        savePayloadToServer()
                                                .toBlocking()
                                                .subscribe(aBoolean -> {
                                                    // No-op
                                                }, throwable -> {
                                                    // No-op
                                                });
                                        subscriber.onCompleted();
                                    }
                                }

                                @Override
                                public void onFail(String error) {
                                    subscriber.onError(new Throwable(error));
                                }
                            });
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            }
        });
    }

    /**
     * Syncs the {@link info.blockchain.wallet.payload.Payload} to the server, for instance after
     * archiving some addresses.
     *
     * @return boolean indicating success or not
     */
    public Observable<Boolean> savePayloadToServer() {
        return Observable.fromCallable(() -> mPayloadManager.savePayloadToServer())
                .compose(RxUtil.applySchedulers());
    }
}
