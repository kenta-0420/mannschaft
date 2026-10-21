package com.mannschaft.app.resident.transitivetx;

import com.mannschaft.app.proxy.repository.TransitiveProxyRepository;
import com.mannschaft.app.proxy.repository.InheritedProxyRepository;
import com.mannschaft.app.resident.repository.TransitiveResidentRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** L1-G0 の interface 解決と合成注釈を検査する専用検体群。 */
public class L1G0TransactionalFixture {
    /** 呼出 signature を持つ親ポート。 */
    public interface Port { void forward(int value); }
    /** 子 interface も具象実装数へ数えない。 */
    public interface ChildPort extends Port { }
    /** 抽象基底は実装候補に数えず、継承された本体だけ探索する。 */
    public abstract static class AbstractBridge implements ChildPort {
        @Override
        public void forward(int value) { new TransitiveProxyRepository().save(); }
    }
    /** 唯一の具象実装。 */
    public static class SingleBridge extends AbstractBridge { }
    /** 二件目の候補がある場合は展開を打ち切る。 */
    public static class SecondBridge extends AbstractBridge { }
    /** 候補へ含めずに二件目の型を解決グラフへ読み込ませる参照元。 */
    public abstract static class ExcludedImplementationReference {
        public SecondBridge excluded;
    }
    /** override した安全な本体を優先する。 */
    public static class SafeOverrideBridge extends AbstractBridge {
        @Override
        public void forward(int value) { new TransitiveResidentRepository().save(); }
    }
    /** 同名の別 overload へ誤って展開しない検体。 */
    public static class OverloadedBridge implements Port {
        @Override
        public void forward(int value) { new TransitiveResidentRepository().save(); }
        public void forward(String value) { new TransitiveProxyRepository().save(); }
    }
    /** interface 呼び出しを持つTX入口。 */
    public static class InterfaceEntry {
        @Transactional
        public void execute(Port port) { port.forward(1); }
        @Transactional
        public void child(ChildPort port) { port.forward(1); }
        @Transactional
        public void diamond(Port port) { port.forward(1); port.forward(2); }
    }
    /** 通常の具象呼び出しは subclass へ展開しない。 */
    public static class ConcreteBridge {
        public void forward() { new TransitiveResidentRepository().save(); }
    }
    /** 呼ばれていない具象 subclass。 */
    public static class UnsafeConcreteSubclass extends ConcreteBridge {
        @Override
        public void forward() { new TransitiveProxyRepository().save(); }
    }
    /** 静的に解決できる具象呼び出しの入口。 */
    public static class ConcreteEntry {
        @Transactional
        public void execute(ConcreteBridge bridge) { bridge.forward(); }
    }
    /** 既存の静的解決で到達できるdefault本体を保持する。 */
    public interface DefaultPort {
        default void forward() { new TransitiveProxyRepository().save(); }
    }
    /** default本体を継承する一件目。 */
    public static class FirstDefaultBridge implements DefaultPort { }
    /** default本体を継承する二件目。 */
    public static class SecondDefaultBridge implements DefaultPort { }
    /** default呼び出し入口。 */
    public static class DefaultEntry {
        @Transactional
        public void execute(DefaultPort port) { port.forward(); }
    }
    /** 安全な親defaultから子defaultへdispatchする検体。 */
    public interface SafeDefaultParent {
        default void forward() { new TransitiveResidentRepository().save(); }
    }
    /** 唯一の具象実装が継承する越境default。 */
    public interface UnsafeDefaultChild extends SafeDefaultParent {
        @Override
        default void forward() { new TransitiveProxyRepository().save(); }
    }
    /** 子interfaceの越境defaultを継承する具象実装。 */
    public static class ChildDefaultBridge implements UnsafeDefaultChild { }
    /** 子defaultよりクラスの本体を優先する検体。 */
    public static class ClassDefaultOverride implements UnsafeDefaultChild {
        @Override
        public void forward() { new TransitiveResidentRepository().save(); }
    }
    /** 越境する親defaultを安全な子defaultがoverrideする検体。 */
    public interface SafeDefaultChild extends DefaultPort {
        @Override
        default void forward() { new TransitiveResidentRepository().save(); }
    }
    /** 安全な子defaultを継承する具象実装。 */
    public static class SafeChildDefaultBridge implements SafeDefaultChild { }
    /** 親interface型から子defaultへ到達するTX入口。 */
    public static class ChildDefaultEntry {
        @Transactional
        public void execute(SafeDefaultParent port) { port.forward(); }
    }
    /** class overrideから明示的に親defaultへ委譲する検体。 */
    public static class SuperDefaultBridge implements DefaultPort {
        @Override
        public void forward() { DefaultPort.super.forward(); }
    }
    /** 同じorigin・target・行にspecialと通常呼び出しが混在する検体。 */
    public static class MixedDefaultBridge implements DefaultPort {
        private InheritedProxyRepository repository;
        @Override
        public void forward() { repository.saveInherited(); }

        @Transactional
        public void execute(DefaultPort peer) {
            // 同一accessへ畳まれても両本体を失わないことを検査するため同じ行へ置く。
            DefaultPort.super.forward(); peer.forward();
        }
    }
    /** interface を経由して再帰する検体。 */
    public static class RecursiveBridge implements Port {
        private Port next;
        @Override
        public void forward(int value) {
            next.forward(value);
            new TransitiveProxyRepository().save();
        }
    }
    /** 一段の合成TX注釈。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    @Async("module-event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @interface ComposedTx { }
    /** 二段の合成TX注釈。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    @ComposedTx
    public @interface NestedTx { }
    /** tx属性を持たないlistener注釈。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Async("module-event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public @interface PlainListener { }
    /** 非TXの循環メタ注釈の一端。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    @CycleB
    public @interface CycleA { }
    /** 非TXの循環メタ注釈の他端。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    @CycleA
    public @interface CycleB { }
    /** メソッドに一段の合成TXを持つ検体。 */
    public static class ComposedMethodEntry {
        @ComposedTx
        public void execute() { new TransitiveProxyRepository().save(); }
    }
    /** メソッドに多段の合成TXを持つ検体。 */
    public static class NestedMethodEntry {
        @NestedTx
        public void execute() { new TransitiveProxyRepository().save(); }
    }
    /** クラスに一段の合成TXを持つ検体。 */
    @ComposedTx
    public static class ComposedClassEntry {
        public void execute() { new TransitiveProxyRepository().save(); }
    }
    /** クラスに多段の合成TXを持つ検体。 */
    @NestedTx
    public static class NestedClassEntry {
        public void execute() { new TransitiveProxyRepository().save(); }
    }
    /** txを持たないlistenerから別のtxへ委譲する検体。 */
    public static class NonTransactionalListener {
        @PlainListener
        public void safe(SameDomainWriter writer) { writer.execute(); }
        @PlainListener
        public void unsafe(CrossDomainWriter writer) { writer.execute(); }
        @CycleA
        public void cyclicAnnotation() { new TransitiveProxyRepository().save(); }
    }
    /** 同一domain内で完結する別TX。 */
    public static class SameDomainWriter {
        @Transactional
        public void execute() { new TransitiveResidentRepository().save(); }
    }
    /** 呼出先TX自身だけが越境の入口になる検体。 */
    public static class CrossDomainWriter {
        @Transactional
        public void execute() { new TransitiveProxyRepository().save(); }
    }
}
