package com.mannschaft.app.resident.transitivetx;
import org.springframework.transaction.annotation.Transactional;
/** クラスレベル注釈が継承 public メソッドにも適用されるケースの fixture。 */
@Transactional
public class InheritedClassTransactionalFixture extends InheritedTransactionalBase { }