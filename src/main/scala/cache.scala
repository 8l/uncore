package uncore
import Chisel._

case object NSets extends Field[Int]
case object NWays extends Field[Int]
case object IsDM extends Field[Boolean]
case object TagBits extends Field[Int]
case object IdxBits extends Field[Int]
case object OffBits extends Field[Int]
case object UntagBits extends Field[Int]
case object WayBits extends Field[Int]
case object RowBits extends Field[Int]
case object WordBits extends Field[Int]
case object RefillCycles extends Field[Int]
case object Replacer extends Field[() => ReplacementPolicy]

abstract class ReplacementPolicy {
  def way: UInt
  def miss: Unit
  def hit: Unit
}

class RandomReplacement(ways: Int) extends ReplacementPolicy {
  private val replace = Bool()
  replace := Bool(false)
  val lfsr = LFSR16(replace)

  def way = if(ways == 1) UInt(0) else lfsr(log2Up(ways)-1,0)
  def miss = replace := Bool(true)
  def hit = {}
}

abstract class Metadata extends Bundle {
  val tag = Bits(width = params(TagBits))
  val coh: CoherenceMetadata
}

class MetaReadReq extends Bundle {
  val idx  = Bits(width = params(IdxBits))
}

class MetaWriteReq[T <: Metadata](gen: T) extends MetaReadReq {
  val way_en = Bits(width = params(WayBits))
  val data = gen.clone
  override def clone = new MetaWriteReq(gen).asInstanceOf[this.type]
}

class MetadataArray[T <: Metadata](makeRstVal: () => T) extends Module {
  val rstVal = makeRstVal()
  val io = new Bundle {
    val read = Decoupled(new MetaReadReq).flip
    val write = Decoupled(new MetaWriteReq(rstVal.clone)).flip
    val resp = Vec.fill(params(NWays)){rstVal.clone.asOutput}
  }
  val metabits = rstVal.getWidth
  val rst_cnt = Reg(init=UInt(0, log2Up(params(NSets)+1)))
  val rst = rst_cnt < UInt(params(NSets))
  val waddr = Mux(rst, rst_cnt, io.write.bits.idx)
  val wdata = Mux(rst, rstVal, io.write.bits.data).toBits
  val wmask = Mux(rst, SInt(-1), io.write.bits.way_en)
  when (rst) { rst_cnt := rst_cnt+UInt(1) }

  val tag_arr = Mem(UInt(width = metabits*params(NWays)), params(NSets), seqRead = true)
  when (rst || io.write.valid) {
    tag_arr.write(waddr, Fill(params(NWays), wdata), FillInterleaved(metabits, wmask))
  }

  val tags = tag_arr(RegEnable(io.read.bits.idx, io.read.valid))
  for (w <- 0 until params(NWays)) {
    val m = tags(metabits*(w+1)-1, metabits*w)
    io.resp(w) := rstVal.clone.fromBits(m)
  }

  io.read.ready := !rst && !io.write.valid // so really this could be a 6T RAM
  io.write.ready := !rst
}

trait HasL2Id extends Bundle {
  val id = UInt(width  = log2Up(params(NTransactors)))
}

trait HasL2InternalRequestState extends Bundle {
  val tag_match = Bool()
  val old_meta = new L2Metadata
  val way_en = Bits(width = params(NWays))
}

object L2Metadata {
  def apply(tag: Bits, coh: MasterMetadata) = {
    val meta = new L2Metadata
    meta.tag := tag
    meta.coh := coh
    meta
  }
}
class L2Metadata extends Metadata {
  val coh = params(TLCoherence).masterMetadataOnFlush.clone
}

class L2MetaReadReq extends MetaReadReq with HasL2Id {
  val tag = Bits(width = params(TagBits))
}

class L2MetaWriteReq extends MetaWriteReq[L2Metadata](new L2Metadata)
  with HasL2Id

class L2MetaResp extends Bundle
  with HasL2Id 
  with HasL2InternalRequestState

class L2MetadataArray extends Module {
  val (co, ways) = (params(TLCoherence), params(NWays))
  val io = new Bundle {
    val read = Decoupled(new L2MetaReadReq).flip
    val write = Decoupled(new L2MetaWriteReq).flip
    val resp = Valid(new L2MetaResp)
  }

  val meta = Module(new MetadataArray(() => L2Metadata(UInt(0), co.masterMetadataOnFlush)))
  meta.io.read <> io.read
  meta.io.write <> io.write
  
  val s1_clk_en = Reg(next = io.read.fire())
  val s1_tag = RegEnable(io.read.bits.tag, io.read.valid)
  val s1_id = RegEnable(io.read.bits.id, io.read.valid)
  def wayMap[T <: Data](f: Int => T) = Vec((0 until ways).map(f))
  val s1_tag_eq_way = wayMap((w: Int) => meta.io.resp(w).tag === s1_tag)
  val s1_tag_match_way = wayMap((w: Int) => s1_tag_eq_way(w) && co.isValid(meta.io.resp(w).coh)).toBits
  val s2_tag_match_way = RegEnable(s1_tag_match_way, s1_clk_en)
  val s2_tag_match = s2_tag_match_way.orR
  val s2_hit_coh = Mux1H(s2_tag_match_way, wayMap((w: Int) => RegEnable(meta.io.resp(w).coh, s1_clk_en)))
  //val s2_hit = s2_tag_match && tl.co.isHit(s2_req.cmd, s2_hit_state) && s2_hit_state === tl.co.newStateOnHit(s2_req.cmd, s2_hit_state)

  val replacer = params(Replacer)()
  val s1_replaced_way_en = UIntToOH(replacer.way)
  val s2_replaced_way_en = UIntToOH(RegEnable(replacer.way, s1_clk_en))
  val s2_repl_meta = Mux1H(s2_replaced_way_en, wayMap((w: Int) => 
    RegEnable(meta.io.resp(w), s1_clk_en && s1_replaced_way_en(w))).toSeq)

  io.resp.valid := Reg(next = s1_clk_en)
  io.resp.bits.id := RegEnable(s1_id, s1_clk_en)
  io.resp.bits.tag_match := s2_tag_match
  io.resp.bits.old_meta := Mux(s2_tag_match, 
    L2Metadata(s2_repl_meta.tag, s2_hit_coh), 
    s2_repl_meta)
  io.resp.bits.way_en := Mux(s2_tag_match, s2_tag_match_way, s2_replaced_way_en)
}

class L2DataReadReq extends Bundle with HasL2Id {
  val way_en = Bits(width = params(NWays))
  val addr   = Bits(width = params(TLAddrBits))
}

class L2DataWriteReq extends L2DataReadReq {
  val wmask  = Bits(width = params(TLWriteMaskBits))
  val data   = Bits(width = params(TLDataBits))
}

class L2DataResp extends Bundle with HasL2Id {
  val data   = Bits(width = params(TLDataBits))
}

class L2DataArray extends Module {
  val io = new Bundle {
    val read = Decoupled(new L2DataReadReq).flip
    val write = Decoupled(new L2DataWriteReq).flip
    val resp = Valid(new L2DataResp)
  }

  val waddr = io.write.bits.addr
  val raddr = io.read.bits.addr
  val wmask = FillInterleaved(params(WordBits), io.write.bits.wmask)
  val resp = (0 until params(NWays)).map { w =>
    val array = Mem(Bits(width=params(RowBits)), params(NSets)*params(RefillCycles), seqRead = true)
    when (io.write.bits.way_en(w) && io.write.valid) {
      array.write(waddr, io.write.bits.data, wmask)
    }
    array(RegEnable(raddr, io.read.bits.way_en(w) && io.read.valid))
  }
  io.resp.valid := ShiftRegister(io.read.valid, 2)
  io.resp.bits.id := ShiftRegister(io.read.bits.id, 2)
  io.resp.bits.data := Mux1H(ShiftRegister(io.read.bits.way_en, 2), resp)

  io.read.ready := Bool(true)
  io.write.ready := Bool(true)
}

class L2HellaCache(bankId: Int) extends CoherenceAgent {

  require(isPow2(params(NSets)))
  require(isPow2(params(NWays))) 
  require(params(RefillCycles) == 1)

  val tshrfile = Module(new TSHRFile(bankId))
  val meta = Module(new L2MetadataArray)
  val data = Module(new L2DataArray)

  tshrfile.io.inner <> io.inner
  tshrfile.io.meta_read <> meta.io.read
  tshrfile.io.meta_write <> meta.io.write
  tshrfile.io.meta_resp <> meta.io.resp
  tshrfile.io.data_read <> data.io.read
  tshrfile.io.data_write <> data.io.write
  tshrfile.io.data_resp <> data.io.resp
  io.outer <> tshrfile.io.outer
  io.incoherent <> tshrfile.io.incoherent
}


class TSHRFile(bankId: Int) extends Module {
  val (co, nClients) = (params(TLCoherence), params(NClients))
  val io = new Bundle {
    val inner = (new TileLinkIO).flip
    val outer = new UncachedTileLinkIO
    val incoherent = Vec.fill(nClients){Bool()}.asInput
    val meta_read = Decoupled(new L2MetaReadReq)
    val meta_write = Decoupled(new L2MetaWriteReq)
    val meta_resp = Valid(new L2MetaResp).flip
    val data_read = Decoupled(new L2DataReadReq)
    val data_write = Decoupled(new L2DataWriteReq)
    val data_resp = Valid(new L2DataResp).flip
  }

  // Wiring helper funcs
  def doOutputArbitration[T <: Data](out: DecoupledIO[T], ins: Seq[DecoupledIO[T]]) {
    val arb = Module(new RRArbiter(out.bits.clone, ins.size))
    out <> arb.io.out
    arb.io.in zip ins map { case (a, in) => a <> in }
  }

  def doInputRouting[T <: HasL2Id](in: ValidIO[T], outs: Seq[ValidIO[T]]) {
    outs.map(_.bits := in.bits)
    outs.zipWithIndex.map { case (o, i) => o.valid := UInt(i) === in.bits.id }
  }

  // Create TSHRs for outstanding transactions
  val trackerList = (0 until params(NReleaseTransactors)).map { id => 
    Module(new L2VoluntaryReleaseTracker(id, bankId)) 
  } ++ (params(NReleaseTransactors) until params(NTransactors)).map { id => 
    Module(new L2AcquireTracker(id, bankId))
  }
  
  // Propagate incoherence flags
  trackerList.map(_.io.tile_incoherent := io.incoherent.toBits)

  // Handle acquire transaction initiation
  val acquire = io.inner.acquire
  val any_acquire_conflict = trackerList.map(_.io.has_acquire_conflict).reduce(_||_)
  val block_acquires = any_acquire_conflict

  val alloc_arb = Module(new Arbiter(Bool(), trackerList.size))
  for( i <- 0 until trackerList.size ) {
    val t = trackerList(i).io.inner
    alloc_arb.io.in(i).valid := t.acquire.ready
    t.acquire.bits := acquire.bits
    t.acquire.valid := alloc_arb.io.in(i).ready
  }
  acquire.ready := trackerList.map(_.io.inner.acquire.ready).reduce(_||_) && !block_acquires
  alloc_arb.io.out.ready := acquire.valid && !block_acquires

  // Handle probe requests
  doOutputArbitration(io.inner.probe, trackerList.map(_.io.inner.probe))

  // Handle releases, which might be voluntary and might have data
  val release = io.inner.release
  val voluntary = co.isVoluntary(release.bits.payload)
  val any_release_conflict = trackerList.tail.map(_.io.has_release_conflict).reduce(_||_)
  val block_releases = Bool(false)
  val conflict_idx = Vec(trackerList.map(_.io.has_release_conflict)).lastIndexWhere{b: Bool => b}
  //val release_idx = Mux(voluntary, Mux(any_release_conflict, conflict_idx, UInt(0)), release.bits.payload.master_xact_id) // TODO: Add merging logic to allow allocated AcquireTracker to handle conflicts, send all necessary grants, use first sufficient response
  val release_idx = Mux(voluntary, UInt(0), release.bits.payload.master_xact_id)
  for( i <- 0 until trackerList.size ) {
    val t = trackerList(i).io.inner
    t.release.bits := release.bits 
    t.release.valid := release.valid && (release_idx === UInt(i)) && !block_releases
  }
  release.ready := Vec(trackerList.map(_.io.inner.release.ready)).read(release_idx) && !block_releases

  // Reply to initial requestor
  doOutputArbitration(io.inner.grant, trackerList.map(_.io.inner.grant))

  // Free finished transactions
  val ack = io.inner.finish
  trackerList.map(_.io.inner.finish.valid := ack.valid)
  trackerList.map(_.io.inner.finish.bits := ack.bits)
  ack.ready := Bool(true)

  // Arbitrate for the outer memory port
  val outer_arb = Module(new UncachedTileLinkIOArbiterThatPassesId(trackerList.size))
  outer_arb.io.in zip  trackerList map { case(arb, t) => arb <> t.io.outer }
  io.outer <> outer_arb.io.out

  // Local memory
  doOutputArbitration(io.meta_read, trackerList.map(_.io.meta_read))
  doOutputArbitration(io.meta_write, trackerList.map(_.io.meta_write))
  doOutputArbitration(io.data_read, trackerList.map(_.io.data_read))
  doOutputArbitration(io.data_write, trackerList.map(_.io.data_write))
  doInputRouting(io.meta_resp, trackerList.map(_.io.meta_resp))
  doInputRouting(io.data_resp, trackerList.map(_.io.data_resp))

}


abstract class L2XactTracker extends Module {
  val (co, nClients) = (params(TLCoherence),params(NClients))
  val io = new Bundle {
    val inner = (new TileLinkIO).flip
    val outer = new UncachedTileLinkIO
    val tile_incoherent = Bits(INPUT, nClients)
    val has_acquire_conflict = Bool(OUTPUT)
    val has_release_conflict = Bool(OUTPUT)
    val meta_read = Decoupled(new L2MetaReadReq)
    val meta_write = Decoupled(new L2MetaWriteReq)
    val meta_resp = Valid(new L2MetaResp).flip
    val data_read = Decoupled(new L2DataReadReq)
    val data_write = Decoupled(new L2DataWriteReq)
    val data_resp = Valid(new L2DataResp).flip
  }

  val c_acq = io.inner.acquire.bits
  val c_rel = io.inner.release.bits
  val c_gnt = io.inner.grant.bits
  val c_ack = io.inner.finish.bits
  val m_gnt = io.outer.grant.bits

}

class L2VoluntaryReleaseTracker(trackerId: Int, bankId: Int) extends L2XactTracker {
  val s_idle :: s_mem :: s_ack :: s_busy :: Nil = Enum(UInt(), 4)
  val state = Reg(init=s_idle)
  val xact  = Reg{ new Release }
  val init_client_id = Reg(init=UInt(0, width = log2Up(nClients)))
  val new_meta = Reg(new L2Metadata)
  val incoming_rel = io.inner.release.bits

  io.has_acquire_conflict := Bool(false)
  io.has_release_conflict := co.isCoherenceConflict(xact.addr, incoming_rel.payload.addr) && 
                               (state != s_idle)

  io.outer.grant.ready := Bool(false)
  io.outer.acquire.valid := Bool(false)
  io.outer.acquire.bits.header.src := UInt(bankId) 
  //io.outer.acquire.bits.header.dst TODO
  io.outer.acquire.bits.payload := Acquire(co.getUncachedWriteAcquireType,
                                            xact.addr,
                                            UInt(trackerId),
                                            xact.data)
  io.inner.acquire.ready := Bool(false)
  io.inner.probe.valid := Bool(false)
  io.inner.release.ready := Bool(false)
  io.inner.grant.valid := Bool(false)
  io.inner.grant.bits.header.src := UInt(bankId)
  io.inner.grant.bits.header.dst := init_client_id
  io.inner.grant.bits.payload := Grant(co.getGrantType(xact, UInt(0)),
                                        xact.client_xact_id,
                                        UInt(trackerId))


  switch (state) {
    is(s_idle) {
      io.inner.release.ready := Bool(true)
      when( io.inner.release.valid ) {
        xact := incoming_rel.payload
        init_client_id := incoming_rel.header.src
        state := s_mem
      }
    }
/*
    is(s_meta_read) {
      when(io.meta_read.ready) state := s_meta_resp
    }
    is(s_meta_resp) {
      when(io.meta_resp.valid) {
        new_meta := L2Metadata(io.meta.resp.bits.old_meta.tag, io.meta.resp.bits.old_meta.sharers, io.meta.resp.bits
        old_meta := io.meta.resp.bits.old_meta
        state := Mux(s_meta_write
Mux(co.messageHasData(xact), s_mem, s_ack)
    }
  */  
    is(s_mem) {
      io.outer.acquire.valid := Bool(true)
      when(io.outer.acquire.ready) { state := s_ack }
    }
    is(s_ack) {
      io.inner.grant.valid := Bool(true)
      when(io.inner.grant.ready) { state := s_idle }
    }
  }
}

class L2AcquireTracker(trackerId: Int, bankId: Int) extends L2XactTracker {
  val s_idle :: s_probe :: s_mem_read :: s_mem_write :: s_make_grant :: s_busy :: Nil = Enum(UInt(), 6)
  val state = Reg(init=s_idle)
  val xact  = Reg{ new Acquire }
  val init_client_id = Reg(init=UInt(0, width = log2Up(nClients)))
  //TODO: Will need id reg for merged release xacts

  val init_sharer_cnt = Reg(init=UInt(0, width = log2Up(nClients)))
  val release_count = if(nClients == 1) UInt(0) else Reg(init=UInt(0, width = log2Up(nClients)))
  val probe_flags = Reg(init=Bits(0, width = nClients))
  val curr_p_id = PriorityEncoder(probe_flags)

  val pending_outer_write = co.messageHasData(xact)
  val pending_outer_read = co.requiresOuterRead(xact.a_type)
  val outer_write_acq = Acquire(co.getUncachedWriteAcquireType, 
                                       xact.addr, UInt(trackerId), xact.data)
  val outer_write_rel = Acquire(co.getUncachedWriteAcquireType, 
                                       xact.addr, UInt(trackerId), c_rel.payload.data)
  val outer_read = Acquire(co.getUncachedReadAcquireType, xact.addr, UInt(trackerId))

  val probe_initial_flags = Bits(width = nClients)
  probe_initial_flags := Bits(0)
  if (nClients > 1) {
    // issue self-probes for uncached read xacts to facilitate I$ coherence
    val probe_self = Bool(true) //co.needsSelfProbe(io.inner.acquire.bits.payload)
    val myflag = Mux(probe_self, Bits(0), UIntToOH(c_acq.header.src(log2Up(nClients)-1,0)))
    probe_initial_flags := ~(io.tile_incoherent | myflag)
  }

  io.has_acquire_conflict := co.isCoherenceConflict(xact.addr, c_acq.payload.addr) && (state != s_idle)
  io.has_release_conflict := co.isCoherenceConflict(xact.addr, c_rel.payload.addr) && (state != s_idle)

  io.outer.acquire.valid := Bool(false)
  io.outer.acquire.bits.header.src := UInt(bankId)
  //io.outer.acquire.bits.header.dst TODO
  io.outer.acquire.bits.payload := outer_read 
  io.outer.grant.ready := io.inner.grant.ready

  io.inner.probe.valid := Bool(false)
  io.inner.probe.bits.header.src := UInt(bankId)
  io.inner.probe.bits.header.dst := curr_p_id
  io.inner.probe.bits.payload := Probe(co.getProbeType(xact.a_type, co.masterMetadataOnFlush),
                                               xact.addr,
                                               UInt(trackerId))

  val grant_type = co.getGrantType(xact.a_type, init_sharer_cnt)
  io.inner.grant.valid := Bool(false)
  io.inner.grant.bits.header.src := UInt(bankId)
  io.inner.grant.bits.header.dst := init_client_id
  io.inner.grant.bits.payload := Grant(grant_type,
                                        xact.client_xact_id,
                                        UInt(trackerId),
                                        m_gnt.payload.data)

  io.inner.acquire.ready := Bool(false)
  io.inner.release.ready := Bool(false)

  switch (state) {
    is(s_idle) {
      io.inner.acquire.ready := Bool(true)
      val needs_outer_write = co.messageHasData(c_acq.payload)
      val needs_outer_read = co.requiresOuterRead(c_acq.payload.a_type)
      when( io.inner.acquire.valid ) {
        xact := c_acq.payload
        init_client_id := c_acq.header.src
        init_sharer_cnt := UInt(nClients) // TODO: Broadcast only
        probe_flags := probe_initial_flags
        if(nClients > 1) {
          release_count := PopCount(probe_initial_flags)
          state := Mux(probe_initial_flags.orR, s_probe,
                    Mux(needs_outer_write, s_mem_write,
                      Mux(needs_outer_read, s_mem_read, s_make_grant)))
        } else state := Mux(needs_outer_write, s_mem_write,
                        Mux(needs_outer_read, s_mem_read, s_make_grant))
      }
    }
    is(s_probe) {
      // Generate probes
      io.inner.probe.valid := probe_flags.orR
      when(io.inner.probe.ready) {
        probe_flags := probe_flags & ~(UIntToOH(curr_p_id))
      }

      // Handle releases, which may have data to be written back
      when(io.inner.release.valid) {
        when(co.messageHasData(c_rel.payload)) {
          io.outer.acquire.valid := Bool(true)
          io.outer.acquire.bits.payload := outer_write_rel
          when(io.outer.acquire.ready) {
            io.inner.release.ready := Bool(true)
            if(nClients > 1) release_count := release_count - UInt(1)
            when(release_count === UInt(1)) {
              state := Mux(pending_outer_write, s_mem_write,
                        Mux(pending_outer_read, s_mem_read, s_make_grant))
            }
          }
        } .otherwise {
          io.inner.release.ready := Bool(true)
          if(nClients > 1) release_count := release_count - UInt(1)
          when(release_count === UInt(1)) {
            state := Mux(pending_outer_write, s_mem_write, 
                      Mux(pending_outer_read, s_mem_read, s_make_grant))
          }
        }
      }
    }
    is(s_mem_read) {
      io.outer.acquire.valid := Bool(true)
      io.outer.acquire.bits.payload := outer_read
      when(io.outer.acquire.ready) {
        state := Mux(co.requiresAckForGrant(grant_type), s_busy, s_idle)
      }
    }
    is(s_mem_write) {
      io.outer.acquire.valid := Bool(true)
      io.outer.acquire.bits.payload := outer_write_acq
      when(io.outer.acquire.ready) { 
        state := Mux(pending_outer_read, s_mem_read, s_make_grant)
      }
    }
    is(s_make_grant) {
      io.inner.grant.valid := Bool(true)
      when(io.inner.grant.ready) { 
        state := Mux(co.requiresAckForGrant(grant_type), s_busy, s_idle)
      }
    }
    is(s_busy) { // Nothing left to do but wait for transaction to complete
      when(io.outer.grant.valid && m_gnt.payload.client_xact_id === UInt(trackerId)) {
        io.inner.grant.valid := Bool(true)
      }
      when(io.inner.finish.valid && c_ack.payload.master_xact_id === UInt(trackerId)) {
        state := s_idle
      }
    }
  }
}
