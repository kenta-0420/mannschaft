"""Bounded parser/classifier checks; outputs classifications only, never XML body text."""
import json, tempfile
from pathlib import Path

import evidence

CONDITION = ('Native週DATE条件: driver=9.6.0 JVM=Asia/Tokyo connectionTimeZone=UTC '
             'driverDefaultA=UTC driverDefaultB=Asia/Tokyo DBoffset=UTC')
def xml(case, failure='', skipped=False, body_in_system_out=True):
    fail_count=1 if failure else 0; skip_count=1 if skipped else 0
    outcome='<failure type="AssertionError">'+failure+'</failure>' if failure else ('<skipped/>' if skipped else '')
    system=('<system-out>'+CONDITION+'</system-out>') if body_in_system_out else ''
    return (f'<testsuite tests="1" failures="{fail_count}" errors="0" skipped="{skip_count}">'
            f'<testcase classname="{case["className"]}" name="{case["method"]}()">{outcome}</testcase>'
            f'{system}</testsuite>').encode('utf-8')

def run():
    checks=[]
    with tempfile.TemporaryDirectory(dir=evidence.BASE) as tmp:
        target=Path(tmp)/'x.xml'
        for case in evidence.CASES:
            if case['kind']=='duplicate':
                body='実MySQL DATEのUTC月曜 同一週のwitness件数 同一週のoutbox件数 同一UTC週の再受付'
            else:
                body='実MySQL DATEのUTC月曜 同entry再受付の例外なし uk_reflection_ranch_event Duplicate entry'
            target.write_bytes(xml(case,body)); rows,counters=evidence.parse_xml(target)
            conditions=evidence.condition_projection(rows[0]['systemOut'])
            outcome,_,_,collision=evidence.classify_case(case,rows[0],counters,conditions)
            checks.append({'caseKey':case['key'],'scenario':'expected_red','result':outcome,
                           'canonicalCollision':collision})
        reflection=evidence.CASES[2]
        for key,label in (('native_saved','本体の保存件数'),('attempt_count','完了attempt件数')):
            body='実MySQL DATEのUTC月曜 同entry再受付の例外なし uk_reflection_ranch_event Duplicate entry '+label
            target.write_bytes(xml(reflection,body)); rows,counters=evidence.parse_xml(target)
            outcome,_,_,_=evidence.classify_case(reflection,rows[0],counters,evidence.condition_projection(rows[0]['systemOut']))
            checks.append({'scenario':'reflection_unexpected_count_failure','guardedCategory':key,'result':outcome})
        case=evidence.CASES[0]
        target.write_bytes(xml(case)); rows,counters=evidence.parse_xml(target)
        checks.append({'scenario':'passed','result':evidence.classify_case(case,rows[0],counters,evidence.condition_projection(rows[0]['systemOut']))[0]})
        target.write_bytes(xml(case,'fixture/setup failed')); rows,counters=evidence.parse_xml(target)
        checks.append({'scenario':'fixture_failure','result':evidence.classify_case(case,rows[0],counters,evidence.condition_projection(rows[0]['systemOut']))[0]})
        target.write_bytes(xml(case,skipped=True)); rows,counters=evidence.parse_xml(target)
        checks.append({'scenario':'skip','result':evidence.classify_case(case,rows[0],counters,evidence.condition_projection(rows[0]['systemOut']))[0]})
        target.write_bytes(xml(case).replace(case['className'].encode(),b'other.TestClass'))
        rows,_=evidence.parse_xml(target)
        checks.append({'scenario':'othercase','descriptorMatches':rows[0]['className']==case['className']})
        target.write_bytes(b'<!DOCTYPE testsuite [<!ENTITY x "x">]><testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        try: evidence.parse_xml(target); dtd='ACCEPTED'
        except ValueError as error: dtd=str(error)
        checks.append({'scenario':'dtd','result':dtd})
        target.write_bytes(b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="c" name="m"><failure>'+b'x'*(8*1024*1024+1)+b'</failure></testcase></testsuite>')
        try: evidence.parse_xml(target); limit='ACCEPTED'
        except ValueError as error: limit=str(error)
        checks.append({'scenario':'text_limit','result':limit})
    return checks

if __name__=='__main__':
    print(json.dumps(run(),ensure_ascii=False,sort_keys=True))
