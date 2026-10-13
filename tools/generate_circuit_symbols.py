"""Generate Lyra's pinned offline symbol catalog. No network access during APK builds.

Requires schemdraw==0.23 (MIT). Run with --vendor app/build/circuit-vendor after
installing that pinned package there with pip --no-deps --target.
"""
import argparse
import json
import math
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--vendor', type=Path)
args = parser.parse_args()
if args.vendor:
    sys.path.insert(0, str(args.vendor.resolve()))
import schemdraw
from schemdraw import elements as elm
assert schemdraw.__version__ == '0.23', 'Use pinned Schemdraw 0.23'

SCALE = 80 / 3
symbols = {}
def add(name, title, category, pins, body=None, bounds=None, **extra):
    assert name not in symbols
    assert all(math.isfinite(n) for p in pins.values() for n in p)
    symbols[name] = dict(name=title, category=category, pins=pins, **extra)
    if body is not None:
        symbols[name]['body'] = body
        b = bounds or [-60, -40, 60, 40]
        symbols[name]['bounds'] = [min([b[0]]+[p[0] for p in pins.values()]),min([b[1]]+[p[1] for p in pins.values()]),
            max([b[2]]+[p[0] for p in pins.values()]),max([b[3]]+[p[1] for p in pins.values()])]

def node(tag, **attrs):
    return dict(tag=tag, attrs=attrs)
def line(x1, y1, x2, y2, **attrs):
    return node('line', x1=x1, y1=y1, x2=x2, y2=y2, **attrs)
def text(x, y, value, size=16):
    return dict(tag='text', attrs={'x':x,'y':y,'text-anchor':'middle','font-size':size,'fill':'@ink','stroke':'none'}, text=value, upright=True)
def rect(x, y, w, h):
    return node('rect', x=x, y=y, width=w, height=h, fill='@background')
def circle(x, y, r):
    return node('circle', cx=x, cy=y, r=r, fill='@background')

# Preserve existing pin positions and Markdown histories exactly.
two = {'a':[-40,0], 'b':[40,0]}
for name in ['resistor','photoresistor','capacitor','polarized_capacitor','inductor','diode','zener','led','photodiode','switch','battery','voltage_source','ac_source','lamp','fuse']:
    add(name, name.replace('_',' ').title(), 'basic', two)
for name,pins in {
    'potentiometer':dict(two,w=[0,40]), 'ground':{'p':[0,-40]}, 'terminal':{'p':[0,0]},
    'npn':{'b':[-40,0],'c':[20,-40],'e':[20,40]},'pnp':{'b':[-40,0],'c':[20,-40],'e':[20,40]},
    'nmos':{'g':[-40,0],'d':[20,-40],'s':[20,40]},'pmos':{'g':[-40,0],'d':[20,-40],'s':[20,40]},
    'triac':{'mt2':[-40,0],'mt1':[40,0],'g':[20,40]},'scr':{'a':[-40,0],'k':[40,0],'g':[20,40]},
    'opamp':{'in+':[-60,20],'in-':[-60,-20],'out':[60,0],'v+':[0,-40],'v-':[0,40]},
}.items():
    add(name,name.replace('_',' ').title(),'basic',pins)
for name, size in [('ic',[120,160]),('block',[160,120]),('connector',[80,160]),('converter',[160,120])]:
    add(name,name.title(),'modules',{},dynamic=True,size=size)

ALLOWED_TAGS = {'path','polyline','polygon','line','rect','circle','ellipse','g','text','tspan'}
ALLOWED_ATTRS = {'d','points','x','y','x1','y1','x2','y2','cx','cy','r','rx','ry','width','height','transform',
    'fill','stroke','stroke-width','stroke-linecap','stroke-linejoin','stroke-dasharray','font-size',
    'font-family','text-anchor','dominant-baseline','dy','dx','text-decoration'}
def xml_node(element):
    tag=element.tag.split('}')[-1]
    assert tag in ALLOWED_TAGS, tag
    attrs=dict(element.attrib)
    style=attrs.pop('style','')
    for item in style.split(';'):
        if ':' in item:
            key,value=item.split(':',1)
            if key == 'stroke-dasharray' and value == '-':
                continue
            attrs[key]=value
    assert set(attrs)<=ALLOWED_ATTRS, (tag,set(attrs)-ALLOWED_ATTRS)
    for key,value in list(attrs.items()):
        if key in ('stroke','fill'):
            attrs[key]={'black':'@ink','white':'@background'}.get(value,value)
        elif key=='stroke-width':
            attrs[key]=2
    if tag in ('text','tspan'):
        # Standalone upstream SVGs have no inherited stroke. Our root does.
        attrs['stroke']='none'
    result=dict(tag=tag,attrs=attrs)
    if element.text and element.text.strip():
        result['text']=element.text
    if len(element):
        result['children']=[xml_node(child) for child in element]
    return result

def imported(name, class_name, category, anchors=None, kwargs=None, extend=None):
    d=schemdraw.Drawing(canvas='svg',show=False,unit=3,inches_per_unit=SCALE/72,fontsize=14)
    component=getattr(elm,class_name)(**(kwargs or {})).at((0,0)).right()
    d.add(component)
    box=d.get_bbox()
    # Standard two-terminal symbols retain horizontal a/b pins at +/-40.
    cx,cy=(box.xmin+box.xmax)/2,(box.ymin+box.ymax)/2
    if anchors is None:
        anchors={'a':'start','b':'end'}
        cx,cy=(component.absanchors['start'].x+component.absanchors['end'].x)/2,(component.absanchors['start'].y+component.absanchors['end'].y)/2
    origin=[cx*SCALE,-cy*SCALE]
    pins={key:[round(component.absanchors[value].x*SCALE-origin[0],3),round(-component.absanchors[value].y*SCALE-origin[1],3)] for key,value in anchors.items()}
    body=[dict(tag='g',attrs={'transform':f'translate({-origin[0]} {-origin[1]})'},
        children=[xml_node(child) for child in ET.fromstring(d.get_imagedata('svg'))])]
    bounds=[box.xmin*SCALE-origin[0],-box.ymax*SCALE-origin[1],box.xmax*SCALE-origin[0],-box.ymin*SCALE-origin[1]]
    for pin,delta in (extend or {}).items():
        old=pins[pin];new=[round(old[0]+delta[0],3),round(old[1]+delta[1],3)]
        body.append(line(*old,*new));pins[pin]=new
        bounds=[min(bounds[0],new[0]),min(bounds[1],new[1]),max(bounds[2],new[0]),max(bounds[3],new[1])]
    add(name,name.replace('_',' ').title(),category,pins,body,[round(n,3) for n in bounds],source=f'Schemdraw 0.23/{class_name}')

for name, cls in {
    'variable_resistor':'ResistorVarIEC','thermistor':'Thermistor','variable_capacitor':'CapacitorVar',
    'trimmer_capacitor':'CapacitorTrim','crystal':'Crystal','schottky':'Schottky','tunnel_diode':'DiodeTunnel',
    'tvs_diode':'DiodeTVS','varactor':'Varactor','diac':'Diac','circuit_breaker':'Breaker',
    'current_source':'SourceI','controlled_voltage_source':'SourceControlledV','controlled_current_source':'SourceControlledI',
    'pulse_source':'SourcePulse','square_source':'SourceSquare','triangle_source':'SourceTriangle','ramp_source':'SourceRamp',
    'solar_cell':'Solar','neon_lamp':'Neon','reed_switch':'SwitchReed',
}.items():
    imported(name,cls,'discrete')
imported('inductor_core','Inductor','passive',kwargs={'core':True})
imported('push_button','Button','switching')
imported('push_button_nc','Button','switching',kwargs={'nc':True})
for name, cls in [('signal_ground','GroundSignal'),('chassis_ground','GroundChassis'),('antenna','Antenna'),('loop_antenna','AntennaLoop')]:
    imported(name,cls,'connections',anchors={'p':'start'})
for name, cls in [('speaker','Speaker'),('microphone','Mic')]:
    imported(name,cls,'transducers',anchors={'a':'in1','b':'in2'},extend={'a':[-20,0],'b':[-20,0]})
for name, cls in [('n_jfet','JFetN'),('p_jfet','JFetP'),('n_igbt','IgbtN'),('p_igbt','IgbtP'),('phototransistor','NpnPhoto')]:
    anchors={'g':'gate','d':'drain','s':'source'} if 'jfet' in name else {'g':'base','c':'collector','e':'emitter'}
    if name=='phototransistor':anchors={'b':'base','c':'collector','e':'emitter'}
    imported(name,cls,'semiconductors',anchors=anchors)
imported('switch_spdt','SwitchSpdt2','switching',anchors={'c':'a','t1':'b','t2':'c'})
imported('switch_dpst','SwitchDpst','switching',anchors={'a1':'p1','b1':'t1','a2':'p2','b2':'t2'})
imported('switch_dpdt','SwitchDpdt','switching',anchors={'c1':'p1','no1':'t1','nc1':'t2','c2':'p2','no2':'t3','nc2':'t4'})
imported('relay','Relay','switching',anchors={'coil1':'in1','coil2':'in2','c':'a','no':'b'})
imported('relay_spdt','Relay','switching',anchors={'coil1':'in1','coil2':'in2','c':'a','no':'b','nc':'c'},kwargs={'switch':'spdt'})
imported('optocoupler','Optocoupler','semiconductors',anchors={'a':'anode','k':'cathode','c':'collector','e':'emitter'})
imported('voltage_regulator','VoltageRegulator','modules',anchors={'in':'in','out':'out','gnd':'gnd'})
imported('shunt_resistor','Rshunt','instruments',anchors={'a':'start','b':'end','sense1':'v1','sense2':'v2'})
for name, kwargs, taps in [('transformer',{},{}),('air_core_transformer',{'core':False},{}),('center_tapped_transformer',{}, {'ct':'tapS2'}),
    ('three_phase_transformer',{'t1':(3,3,3),'t2':(3,3,3)},{})]:
    anchors={p:p for p in (['p1','p2','p3','p4','p5','p6','s1','s2','s3','s4','s5','s6'] if 'three_phase' in name else ['p1','p2','s1','s2'])}
    anchors.update(taps)
    imported(name,'Transformer','magnetics',anchors=anchors,kwargs=kwargs,extend={p:([-20,0] if p.startswith('p') else [20,0]) for p in anchors})

# IEC-style functional symbols, drawn by Lyra rather than claiming the entire
# subscription IEC 60617 database is bundled or certified.
for name, glyph in [('motor','M'),('dc_motor','M\n='),('ac_motor','M\n~'),('three_phase_motor','M\n3~'),('generator','G'),
    ('ammeter','A'),('voltmeter','V'),('ohmmeter','Ω'),('frequency_meter','Hz'),('power_factor_meter','cos φ'),('energy_meter','Wh')]:
    rows=glyph.split('\n')
    body=[line(-40,0,-24,0),line(24,0,40,0),circle(0,0,24)]
    body.extend(text(0,5 if len(rows)==1 else -2,rows[0],14 if len(rows[0])>2 else 18) for _ in [0])
    if len(rows)>1:body.append(text(0,15,rows[1],12))
    add(name,name.replace('_',' ').title(),'machines' if 'motor' in name or name=='generator' else 'instruments',two,body,[-40,-24,40,24])
add('wattmeter','Wattmeter','instruments',{'i+':[-50,-16],'i-':[50,-16],'v+':[-50,16],'v-':[50,16]},
    [circle(0,0,28),text(0,6,'W',18),line(-50,-16,-23,-16),line(23,-16,50,-16),line(-50,16,-23,16),line(23,16,50,16)],[-50,-28,50,28])
for name, marker in [('varistor','U'),('ntc_thermistor','−t°'),('ptc_thermistor','+t°'),('trimmer_resistor','trim')]:
    add(name,name.replace('_',' ').title(),'passive',two,[line(-40,0,-20,0),line(20,0,40,0),rect(-20,-8,40,16),
        line(-24,18,24,-18),text(27,-20,marker,11)],[-40,-34,45,18])
symbols['trimmer_resistor']['body'].pop()
symbols['trimmer_resistor']['body'].append(line(18,-25,30,-11))
symbols['three_phase_motor']['pins']={'u':[-50,-16],'v':[-50,0],'w':[-50,16],'pe':[0,40]}
symbols['three_phase_motor']['body']=[circle(0,0,24),text(0,-2,'M',18),text(0,15,'3~',12),
    line(-50,-16,-math.sqrt(24**2-16**2),-16),line(-50,0,-24,0),line(-50,16,-math.sqrt(24**2-16**2),16),line(0,24,0,40)]
symbols['three_phase_motor']['bounds']=[-50,-24,24,40]
add('autotransformer','Autotransformer','magnetics',{'a':[-40,-40],'b':[-40,40],'tap':[40,0]},
    [line(-40,-40,-16,-40),line(-40,40,-16,40),line(-16,0,40,0),line(2,-40,2,40),line(8,-40,8,40),
     node('path',d='M-16,-40 Q-36,-30 -16,-20 Q-36,-10 -16,0 Q-36,10 -16,20 Q-36,30 -16,40')],[-40,-40,40,40])
add('comparator','Comparator','semiconductors',{'in+':[-60,20],'in-':[-60,-20],'out':[60,0],'v+':[0,-40],'v-':[0,40]},
    [node('path',d='M-40,-35 L40,0 L-40,35 Z',fill='@background'),line(-60,-20,-40,-20),line(-60,20,-40,20),line(40,0,60,0),
     line(0,-40,0,-17.5),line(0,17.5,0,40),text(-28,-15,'−',14),text(-28,25,'+',14)],[-60,-40,60,40])
add('variable_inductor','Variable inductor','passive',two,[line(-40,0,-24,0),line(24,0,40,0),
    node('path',d='M-24,0 C-24,-20 -12,-20 -12,0 C-12,-20 0,-20 0,0 C0,-20 12,-20 12,0 C12,-20 24,-20 24,0'),
    line(-25,18,25,-26),node('path',d='M25,-26 L17,-23 L22,-18 Z',fill='@ink')],[-40,-26,40,18])
for name,nc in [('contact_no',False),('contact_nc',True)]:
    body=[line(-40,0,-10,0),line(10,0,40,0),line(-10,-20,-10,20),line(10,-20,10,20)]
    if nc:body.append(line(-20,22,20,-22))
    add(name,name.replace('_',' ').title(),'switching',two,body,[-40,-22,40,22])
add('relay_coil','Relay coil','switching',two,[line(-40,0,-20,0),line(20,0,40,0),rect(-20,-14,40,28)],[-40,-14,40,14])
add('switch_nc','Normally closed switch','switching',two,[line(-40,0,-18,0),line(18,0,40,0),circle(-18,0,3),circle(18,0,3),line(-15,0,15,0),line(18,-10,18,10)],[-40,-10,40,10])

def box_symbol(name,title,category,pins,mark,inverts=(),clocks=()):
    labelled=name in ['d_flipflop','jk_flipflop','sr_latch','multiplexer','demultiplexer','counter','shift_register']
    extent=70 if labelled else 40
    edge_y=50 if labelled else 40
    pins={pin:[-(extent+20) if p[0]<0 else extent+20 if p[0]>0 else 0,p[1]] for pin,p in pins.items()}
    body=[rect(-extent,-edge_y,extent*2,edge_y*2),text(0,5,mark)]
    for pin,p in pins.items():
        x,y=p;ix=max(-extent,min(extent,x));iy=max(-edge_y,min(edge_y,y))
        line_end=[ix,iy]
        if pin in inverts:
            cx=ix+(-4 if x<0 else 4);body.append(circle(cx,iy,4));line_end=[cx+(-4 if x<0 else 4),iy]
        body.append(line(x,y,*line_end))
        if pin in clocks:body.append(node('path',d=f'M{ix},{iy-5} L{ix+8},{iy} L{ix},{iy+5}'))
        if labelled:
            captions={'in1':'D0','in2':'D1','in3':'D2','out1':'Q0','out2':'Q1','out3':'Q2','sel':'S','qbar':'Q'}
            caption=text(ix+(18 if pin in clocks else 6) if x<0 else ix-6 if x>0 else ix,iy+4 if x else iy-8,captions.get(pin,pin.upper()),11)
            caption['attrs']['text-anchor']='start' if x<0 else 'end' if x>0 else 'middle'
            if pin=='qbar':caption['attrs']['text-decoration']='overline'
            body.append(caption)
    add(name,title,category,pins,body,[-extent-20,-edge_y,extent+20,edge_y])
for name,mark,inv in [('and_gate','&',False),('nand_gate','&',True),('or_gate','≥1',False),('nor_gate','≥1',True),('xor_gate','=1',False),('xnor_gate','=1',True)]:
    box_symbol(name,name.replace('_',' ').title(),'logic',{'in1':[-60,-16],'in2':[-60,16],'out':[60,0]},mark,('out',) if inv else ())
for name,mark,inv in [('buffer','1',False),('not_gate','1',True),('schmitt_trigger','hysteresis',False)]:
    box_symbol(name,name.replace('_',' ').title(),'logic',{'in':[-60,0],'out':[60,0]},'1' if mark=='hysteresis' else mark,('out',) if inv else ())
    if name=='schmitt_trigger':symbols[name]['body'].append(node('path',d='M-12,20 L-4,20 L-4,10 L8,10 M-8,10 L0,10 L0,20 L12,20'))
box_symbol('tri_state_buffer','Tri-state buffer','logic',{'in':[-60,0],'out':[60,0],'en':[0,60]},'1')
for name,pins,mark in [
    ('d_flipflop',{'d':[-60,-20],'clk':[-60,20],'q':[60,-20],'qbar':[60,20]},'D'),
    ('jk_flipflop',{'j':[-60,-24],'clk':[-60,0],'k':[-60,24],'q':[60,-20],'qbar':[60,20]},'JK'),
    ('sr_latch',{'s':[-60,-20],'r':[-60,20],'q':[60,-20],'qbar':[60,20]},'SR'),
    ('multiplexer',{'in1':[-60,-24],'in2':[-60,0],'in3':[-60,24],'sel':[0,60],'out':[60,0]},'MUX'),
    ('demultiplexer',{'in':[-60,0],'sel':[0,60],'out1':[60,-24],'out2':[60,0],'out3':[60,24]},'DEMUX'),
    ('counter',{'clk':[-60,-20],'rst':[-60,20],'q0':[60,-24],'q1':[60,0],'q2':[60,24]},'CTR'),
    ('shift_register',{'d':[-60,-20],'clk':[-60,20],'q0':[60,-24],'q1':[60,0],'q2':[60,24]},'SRG'),
]:
    box_symbol(name,name.replace('_',' ').title(),'logic',pins,mark,('qbar',) if 'qbar' in pins else (),('clk',) if 'clk' in pins else ())
for name,left,right in [('rectifier','~','='),('inverter','=','~'),('dc_dc_converter','=','='),('ac_ac_converter','~','~'),
    ('frequency_converter','~','f'),('voltage_frequency_converter','U','f'),('frequency_voltage_converter','f','U'),('adc','A','D'),('dac','D','A')]:
    pins={'in+':[-80,-24],'in-':[-80,24],'out+':[80,-24],'out-':[80,24]}
    body=[rect(-60,-45,120,90),line(-60,45,60,-45),text(-25,-10,left,22),text(25,25,right,22)]
    for x,y in pins.values():body.append(line(x,y,-60 if x<0 else 60,y))
    add(name,name.replace('_',' ').title(),'converters',pins,body,[-80,-45,80,45])
bridge=[line(-60,0,-35,0),line(35,0,60,0),line(0,-60,0,-35),line(0,35,0,60),text(10,-43,'+',13),text(10,49,'−',13)]
for a,k in [((-35,0),(0,-35)),((35,0),(0,-35)),((0,35),(-35,0)),((0,35),(35,0))]:
    angle=math.degrees(math.atan2(k[1]-a[1],k[0]-a[0]));mx,my=(a[0]+k[0])/2,(a[1]+k[1])/2
    half=math.dist(a,k)/2
    bridge.append(dict(tag='g',attrs={'transform':f'translate({mx} {my}) rotate({angle})'},children=[line(-half,0,-6,0),line(6,0,half,0),
        node('path',d='M-6,-6 L6,0 L-6,6 Z',fill='@background'),line(6,-6,6,6)]))
add('bridge_rectifier','Bridge rectifier','converters',{'ac1':[-60,0],'ac2':[60,0],'+':[0,-60],'-':[0,60]},bridge,[-60,-60,60,60])

for name,title in {'variable_resistor':'变阻器','potentiometer':'电位器','trimmer_resistor':'微调电阻','variable_capacitor':'可变电容',
    'variable_inductor':'可变电感','varistor':'压敏电阻','thermistor':'热敏电阻','ntc_thermistor':'负温度系数热敏电阻','ptc_thermistor':'正温度系数热敏电阻',
    'motor':'马达 电机','dc_motor':'直流电机','ac_motor':'交流电机','three_phase_motor':'三相电机','generator':'发电机','ammeter':'电流表',
    'voltmeter':'电压表','ohmmeter':'欧姆表','wattmeter':'功率表','frequency_meter':'频率表','energy_meter':'电能表',
    'transformer':'变压器','air_core_transformer':'空芯变压器','center_tapped_transformer':'中心抽头变压器','three_phase_transformer':'三相变压器',
    'autotransformer':'自耦变压器','relay':'继电器','relay_spdt':'转换继电器','relay_coil':'继电器线圈','contact_no':'常开触点','contact_nc':'常闭触点',
    'optocoupler':'光耦 光电耦合器','voltage_regulator':'稳压器 稳压芯片','shunt_resistor':'分流电阻','comparator':'比较器',
    'block':'功能组块 模块','connector':'连接器','converter':'转换器 转变器','ic':'集成电路 芯片',
    'rectifier':'整流器','bridge_rectifier':'桥式整流器','inverter':'逆变器','dc_dc_converter':'直流转换器','ac_ac_converter':'交流转换器',
    'frequency_converter':'变频器','voltage_frequency_converter':'电压频率转换器','frequency_voltage_converter':'频率电压转换器','adc':'模数转换器','dac':'数模转换器',
    'and_gate':'与门','or_gate':'或门','not_gate':'非门','nand_gate':'与非门','nor_gate':'或非门','xor_gate':'异或门','xnor_gate':'同或门',
    'd_flipflop':'D触发器','jk_flipflop':'JK触发器','sr_latch':'SR锁存器','multiplexer':'多路选择器','demultiplexer':'解复用器','counter':'计数器','shift_register':'移位寄存器'}.items():
    symbols[name]['name_zh']=title
catalog=dict(version=1,upstream={'name':'Schemdraw','version':'0.23','license':'MIT',
    'url':'https://pypi.org/project/schemdraw/0.23/','copyright':'Copyright (c) 2014-2026 Collin J. Delker'},symbols=symbols)
resource=ROOT/'app/src/main/resources/charts/circuit-symbols.json'
resource.parent.mkdir(parents=True,exist_ok=True)
resource.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assets=ROOT/'app/src/main/assets/charts'
(assets/'circuit-symbols.js').write_text('/* Generated by tools/generate_circuit_symbols.py. Schemdraw-derived geometry: MIT; see SCHEMDRAW-LICENSE. Lyra additions: AGPL-3.0. */\nwindow.LyraCircuitSymbols = '+json.dumps(catalog,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf-8')
license_path=Path(schemdraw.__file__).parent.parent/'schemdraw-0.23.dist-info/licenses/LICENSE.txt'
(assets/'SCHEMDRAW-LICENSE').write_text(license_path.read_text(encoding='utf-8'),encoding='utf-8')
print(f'Generated {len(symbols)} symbols ({sum("source" in s for s in symbols.values())} Schemdraw-derived).')

# Gallery fixtures exercise the shipped data in real WebViews and exports.
for suffix,categories in [('power',{'basic','discrete','connections','transducers','semiconductors','switching','magnetics','machines','instruments','passive'}),
                          ('logic',{'logic','converters','modules'})]:
    names=[name for name,definition in symbols.items() if definition['category'] in categories]
    components=[]
    for index,name in enumerate(names):
        c=dict(id=f'X{index+1}',type=name,x=130+(index%6)*250,y=160+(index//6)*220,label=name)
        if symbols[name].get('dynamic'):
            c.update(label=name,mark='模块',pins=[dict(id='IN',side='left',offset=-24,label='IN',clock=True),
                dict(id='RST',side='left',offset=24,label='RST',invert=True,overline=True),dict(id='OUT',side='right',label='OUT')])
            if name=='converter':c['mark']='~ / ='
            if name=='connector':c['width']=160
        components.append(c)
    fixture=dict(width=1540,height=200+math.ceil(len(names)/6)*220,components=components)
    assert fixture['height']<=4096
    file=ROOT/f'app/src/androidTest/assets/charts/circuit-library-{suffix}.json'
    file.write_text(json.dumps(fixture,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
