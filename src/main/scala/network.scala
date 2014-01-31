package uncore
import Chisel._
import scala.collection.mutable.Stack

class PairedDataIO[M <: Data, D <: Data](mType: M, dType: D) extends Bundle {
  val meta = Decoupled(mType)
  val data = Decoupled(dType)
  override def clone = { new PairedDataIO(mType, dType).asInstanceOf[this.type] }
}

class PairedArbiterIO[M <: Data, D <: Data](mType: M, dType: D, n: Int) extends Bundle {
  val in  = Vec.fill(n){new PairedDataIO(mType, dType)}.flip
  val out = new PairedDataIO(mType, dType)  
  val meta_chosen = UInt(OUTPUT, log2Up(n)) 
  val data_chosen = UInt(OUTPUT, log2Up(n)) 
  override def clone = { new PairedArbiterIO(mType, dType, n).asInstanceOf[this.type] }
}

class PairedLockingRRArbiter[M <: Data, D <: Data](mType: M, dType: D, n: Int, count: Int, needsLock: Option[M => Bool] = None) extends Module {
  require(isPow2(count))
  val io = new PairedArbiterIO(mType, dType, n)
  val locked  = if(count > 1) Reg(init=Bool(false)) else Bool(false)
  val lockIdx = if(count > 1) Reg(init=UInt(n-1)) else UInt(n-1)
  val grant = List.fill(n)(Bool())
  val meta_chosen = Bits(width = log2Up(n))

  val chosen_meta_has_data = needsLock.map(_(io.in(meta_chosen).meta.bits)).getOrElse(Bool(true))
  val valid_meta_has_data = io.in(meta_chosen).meta.valid && chosen_meta_has_data
  val grant_chosen_meta = !(locked && chosen_meta_has_data)
  (0 until n).map(i => io.in(i).meta.ready := grant(i) && grant_chosen_meta && io.out.meta.ready)
  (0 until n).map(i => io.in(i).data.ready := Mux(locked, lockIdx === UInt(i), grant(i) && valid_meta_has_data) && io.out.data.ready)
  io.out.meta.valid := io.in(meta_chosen).meta.valid && grant_chosen_meta
  io.out.data.valid := Mux(locked, io.in(lockIdx).data.valid, io.in(meta_chosen).data.valid && valid_meta_has_data)
  io.out.meta.bits := io.in(meta_chosen).meta.bits
  io.out.data.bits := Mux(locked, io.in(lockIdx).data.bits, io.in(meta_chosen).data.bits)
  io.meta_chosen := meta_chosen
  io.data_chosen := Mux(locked, lockIdx, meta_chosen)

  if(count > 1){
    val cnt = Reg(init=UInt(0, width = log2Up(count)))
    val cnt_next = cnt + UInt(1)
    when(io.out.data.fire()){
      cnt := cnt_next
      when(cnt_next === UInt(0)) {
        locked := Bool(false)
      }
    }
    when(io.out.meta.fire()) {
      when(needsLock.map(_(io.out.meta.bits)).getOrElse(Bool(true))) {
        when(!locked) {
          locked := Bool(true)
          lockIdx := Vec(io.in.map{in => in.meta.fire()}).indexWhere{i: Bool => i} 
        }
      }
    }
  }
  val last_grant = Reg(init=Bits(0, log2Up(n)))
  val ctrl = ArbiterCtrl((0 until n).map(i => io.in(i).meta.valid && UInt(i) > last_grant) ++ io.in.map(_.meta.valid))
  (0 until n).map(i => grant(i) := ctrl(i) && UInt(i) > last_grant || ctrl(i + n))

  var choose = Bits(n-1)
  for (i <- n-2 to 0 by -1)
    choose = Mux(io.in(i).meta.valid, Bits(i), choose)
  for (i <- n-1 to 1 by -1)
    choose = Mux(io.in(i).meta.valid && UInt(i) > last_grant, Bits(i), choose)
  meta_chosen := choose

  when (io.out.meta.fire()) { last_grant := meta_chosen }
}

class PairedCrossbarIO[M <: Data, D <: Data](mType: M, dType: D)(implicit conf: PhysicalNetworkConfiguration) extends Bundle {
    val in  = Vec.fill(conf.nEndpoints){new PairedDataIO(new PhysicalNetworkIO(mType),new PhysicalNetworkIO(dType))}.flip 
    val out = Vec.fill(conf.nEndpoints){new PairedDataIO(new PhysicalNetworkIO(mType),new PhysicalNetworkIO(dType))}
}

class PairedCrossbar[M <: Data, D <: Data](mType: M, dType: D, count: Int, needsLock: Option[PhysicalNetworkIO[M] => Bool] = None)(implicit conf: PhysicalNetworkConfiguration) extends PhysicalNetwork(conf) {
  val io = new PairedCrossbarIO(mType, dType)

  val metaRdyVecs = List.fill(conf.nEndpoints)(Vec.fill(conf.nEndpoints){Bool()})
  val dataRdyVecs = List.fill(conf.nEndpoints)(Vec.fill(conf.nEndpoints){Bool()})
  val rdyVecs = metaRdyVecs zip dataRdyVecs

  io.out.zip(rdyVecs).zipWithIndex.map{ case ((out, rdys), i) => {
    val rrarb = Module(new PairedLockingRRArbiter(io.in(0).meta.bits.clone, io.in(0).data.bits.clone, conf.nEndpoints, count, needsLock))
    rrarb.io.in zip io.in zip rdys._1 zip rdys._2 map { case (((arb, in), meta_rdy), data_rdy) => {
      arb.meta.valid := in.meta.valid && (in.meta.bits.header.dst === UInt(i)) 
      arb.meta.bits := in.meta.bits
      meta_rdy := arb.meta.ready && (in.meta.bits.header.dst === UInt(i))
      arb.data.valid := in.data.valid && (in.data.bits.header.dst === UInt(i)) 
      arb.data.bits := in.data.bits
      data_rdy := arb.data.ready && (in.data.bits.header.dst === UInt(i))
    }}
    out <> rrarb.io.out
  }}
  for(i <- 0 until conf.nEndpoints) {
    io.in(i).meta.ready := rdyVecs.map(r => r._1(i)).reduceLeft(_||_)
    io.in(i).data.ready := rdyVecs.map(r => r._2(i)).reduceLeft(_||_)
  }
}

case class PhysicalNetworkConfiguration(nEndpoints: Int, idBits: Int)

class PhysicalHeader(implicit conf: PhysicalNetworkConfiguration) extends Bundle {
  val src = UInt(width = conf.idBits)
  val dst = UInt(width = conf.idBits)
}

class PhysicalNetworkIO[T <: Data](dType: T)(implicit conf: PhysicalNetworkConfiguration) extends Bundle {
  val header = new PhysicalHeader
  val payload = dType.clone
  override def clone = { new PhysicalNetworkIO(dType).asInstanceOf[this.type] }
}

abstract class PhysicalNetwork(conf: PhysicalNetworkConfiguration) extends Module

class BasicCrossbarIO[T <: Data](dType: T)(implicit conf: PhysicalNetworkConfiguration) extends Bundle {
    val in  = Vec.fill(conf.nEndpoints){Decoupled(new PhysicalNetworkIO(dType))}.flip 
    val out = Vec.fill(conf.nEndpoints){Decoupled(new PhysicalNetworkIO(dType))}
}
class BasicCrossbar[T <: Data](dType: T, count: Int = 1)(implicit conf: PhysicalNetworkConfiguration) extends PhysicalNetwork(conf) {
  val io = new BasicCrossbarIO(dType)

  val rdyVecs = List.fill(conf.nEndpoints)(Vec.fill(conf.nEndpoints)(Bool()))

  io.out.zip(rdyVecs).zipWithIndex.map{ case ((out, rdys), i) => {
    val rrarb = Module(new LockingRRArbiter(io.in(0).bits, conf.nEndpoints, count))
    (rrarb.io.in, io.in, rdys).zipped.map{ case (arb, in, rdy) => {
      arb.valid := in.valid && (in.bits.header.dst === UInt(i)) 
      arb.bits := in.bits
      rdy := arb.ready && (in.bits.header.dst === UInt(i))
    }}
    out <> rrarb.io.out
  }}
  for(i <- 0 until conf.nEndpoints) {
    io.in(i).ready := rdyVecs.map(r => r(i)).reduceLeft(_||_)
  }
}

case class LogicalNetworkConfiguration(idBits: Int, nMasters: Int, nClients: Int) {
  val nEndpoints = nMasters + nClients
}

abstract class LogicalNetwork[TileLinkType <: Bundle](implicit conf: LogicalNetworkConfiguration) extends Module

class LogicalHeader(implicit conf: LogicalNetworkConfiguration) extends Bundle {
  val src = UInt(width = conf.idBits)
  val dst = UInt(width = conf.idBits)
}

object FIFOedLogicalNetworkIOWrapper {
  def apply[T <: Data](in: DecoupledIO[T], src: UInt = UInt(0), dst: UInt = UInt(0))(implicit conf: LogicalNetworkConfiguration) = {
    val out = Decoupled(new LogicalNetworkIO(in.bits.clone)).asDirectionless
    out.valid := in.valid
    out.bits.payload := in.bits
    out.bits.header.dst := dst
    out.bits.header.src := src
    in.ready := out.ready
    out
  }
}

object FIFOedLogicalNetworkIOUnwrapper {
  def apply[T <: Data](in: DecoupledIO[LogicalNetworkIO[T]])(implicit conf: LogicalNetworkConfiguration) = {
    val out = Decoupled(in.bits.payload.clone).asDirectionless
    out.valid := in.valid
    out.bits := in.bits.payload
    in.ready := out.ready
    out
  }
}

class LogicalNetworkIO[T <: Data](dType: T)(implicit conf: LogicalNetworkConfiguration) extends Bundle {
  val header = new LogicalHeader
  val payload = dType.clone
  override def clone = { new LogicalNetworkIO(dType).asInstanceOf[this.type] }
}
