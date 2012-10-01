package uncore

import Chisel._
import Constants._

object TransactionInit 
{
  def apply(x_type: Bits, addr: UFix, tile_xact_id: UFix) = {
    val init = new TransactionInit
    init.x_type := x_type
    init.addr := addr
    init.tile_xact_id := tile_xact_id
    init
  }
  def apply(x_type: Bits, addr: UFix, tile_xact_id: UFix, write_mask: Bits) = {
    val init = new TransactionInit
    init.x_type := x_type
    init.addr := addr
    init.tile_xact_id := tile_xact_id
    init.write_mask := write_mask
    init
  }
  def apply(x_type: Bits, addr: UFix, tile_xact_id: UFix, subword_addr: UFix, atomic_opcode: UFix) = {
    val init = new TransactionInit
    init.x_type := x_type
    init.addr := addr
    init.tile_xact_id := tile_xact_id
    init.subword_addr := subword_addr
    init.atomic_opcode := atomic_opcode
    init
  }
}
class TransactionInit extends PhysicalAddress {
  val x_type = Bits(width = X_INIT_TYPE_MAX_BITS)
  val tile_xact_id = Bits(width = TILE_XACT_ID_BITS)
  val write_mask = Bits(width = X_INIT_WRITE_MASK_BITS)
  val subword_addr = Bits(width = X_INIT_SUBWORD_ADDR_BITS)
  val atomic_opcode = Bits(width = X_INIT_ATOMIC_OP_BITS)
}

class TransactionInitData extends MemData

class TransactionAbort extends Bundle {
  val tile_xact_id = Bits(width = TILE_XACT_ID_BITS)
}

class ProbeRequest extends PhysicalAddress {
  val p_type = Bits(width = P_REQ_TYPE_MAX_BITS)
  val global_xact_id = Bits(width = GLOBAL_XACT_ID_BITS)
}

class ProbeReply extends Bundle {
  val p_type = Bits(width = P_REP_TYPE_MAX_BITS)
  val global_xact_id = Bits(width = GLOBAL_XACT_ID_BITS)
}

class ProbeReplyData extends MemData

class TransactionReply extends MemData {
  val x_type = Bits(width = X_REP_TYPE_MAX_BITS)
  val tile_xact_id = Bits(width = TILE_XACT_ID_BITS)
  val global_xact_id = Bits(width = GLOBAL_XACT_ID_BITS)
  val require_ack = Bool()
}

class TransactionFinish extends Bundle {
  val global_xact_id = Bits(width = GLOBAL_XACT_ID_BITS)
}

object cpuCmdToRW {
  def apply(cmd: Bits): (Bool, Bool) = {
    val store   = (cmd === M_XWR)
    val load    = (cmd === M_XRD)
    val amo     = cmd(3).toBool
    val read    = load  || amo || (cmd === M_PFR) || (cmd === M_PFW)
    val write   = store || amo
    (read, write)
  }
}

abstract class CoherencePolicy {
  def isHit (cmd: Bits, state: UFix): Bool
  def isValid (state: UFix): Bool

  def needsTransactionOnSecondaryMiss(cmd: Bits, outstanding: TransactionInit): Bool
  def needsTransactionOnCacheControl(cmd: Bits, state: UFix): Bool
  def needsWriteback (state: UFix): Bool

  def newStateOnHit(cmd: Bits, state: UFix): UFix
  def newStateOnCacheControl(cmd: Bits): UFix
  def newStateOnWriteback(): UFix
  def newStateOnFlush(): UFix
  def newStateOnTransactionReply(incoming: TransactionReply, outstanding: TransactionInit): UFix
  def newStateOnProbeRequest(incoming: ProbeRequest, state: UFix): Bits

  def getTransactionInitTypeOnPrimaryMiss(cmd: Bits, state: UFix): UFix
  def getTransactionInitTypeOnSecondaryMiss(cmd: Bits, state: UFix, outstanding: TransactionInit): UFix
  def getTransactionInitTypeOnCacheControl(cmd: Bits): Bits
  def getTransactionInitTypeOnWriteback(): Bits

  def newProbeReply (incoming: ProbeRequest, state: UFix): ProbeReply

  def messageHasData (reply: ProbeReply): Bool
  def messageHasData (init: TransactionInit): Bool
  def messageHasData (reply: TransactionReply): Bool
  def messageUpdatesDataArray (reply: TransactionReply): Bool
  def messageIsUncached(init: TransactionInit): Bool

  def isCoherenceConflict(addr1: Bits, addr2: Bits): Bool
  def getTransactionReplyType(x_type: UFix, count: UFix): Bits
  def getProbeRequestType(x_type: UFix, global_state: UFix): UFix
  def needsMemRead(x_type: UFix, global_state: UFix): Bool
  def needsMemWrite(x_type: UFix, global_state: UFix): Bool
  def needsAckReply(x_type: UFix, global_state: UFix): Bool
}

trait UncachedTransactions {
  def getUncachedReadTransactionInit(addr: UFix, id: UFix): TransactionInit
  def getUncachedWriteTransactionInit(addr: UFix, id: UFix): TransactionInit
  def getUncachedReadWordTransactionInit(addr: UFix, id: UFix): TransactionInit
  def getUncachedWriteWordTransactionInit(addr: UFix, id: UFix, write_mask: Bits): TransactionInit
  def getUncachedAtomicTransactionInit(addr: UFix, id: UFix, subword_addr: UFix, atomic_op: UFix): TransactionInit
}

abstract class CoherencePolicyWithUncached extends CoherencePolicy with UncachedTransactions

abstract class IncoherentPolicy extends CoherencePolicy {
  // UNIMPLEMENTED
  def newStateOnProbeRequest(incoming: ProbeRequest, state: UFix): Bits = state
  def newProbeReply (incoming: ProbeRequest, state: UFix): ProbeReply = { 
    val reply = new ProbeReply()
    reply.p_type := UFix(0)
    reply.global_xact_id := UFix(0)
    reply
  }
  def messageHasData (reply: ProbeReply) = Bool(false)
  def isCoherenceConflict(addr1: Bits, addr2: Bits): Bool = Bool(false)
  def getTransactionReplyType(x_type: UFix, count: UFix): Bits = Bits(0)
  def getProbeRequestType(x_type: UFix, global_state: UFix): UFix = UFix(0)
  def needsMemRead(x_type: UFix, global_state: UFix): Bool = Bool(false)
  def needsMemWrite(x_type: UFix, global_state: UFix): Bool = Bool(false)
  def needsAckReply(x_type: UFix, global_state: UFix): Bool = Bool(false)
}

class ThreeStateIncoherence extends IncoherentPolicy {
  val tileInvalid :: tileClean :: tileDirty :: Nil = Enum(3){ UFix() }
  val xactInitReadClean :: xactInitReadDirty :: xactInitWriteback :: Nil = Enum(3){ UFix() }
  val xactReplyData :: xactReplyAck :: Nil = Enum(2){ UFix() }
  val probeRepInvalidateAck :: Nil = Enum(1){ UFix() }
  val uncachedTypeList = List() 
  val hasDataTypeList = List(xactInitWriteback)

  def isHit ( cmd: Bits, state: UFix): Bool = (state === tileClean || state === tileDirty)
  def isValid (state: UFix): Bool = state != tileInvalid

  def needsTransactionOnSecondaryMiss(cmd: Bits, outstanding: TransactionInit) = Bool(false)
  def needsTransactionOnCacheControl(cmd: Bits, state: UFix): Bool = state === tileDirty
  def needsWriteback (state: UFix): Bool = state === tileDirty

  def newState(cmd: Bits, state: UFix): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, tileDirty, Mux(read, Mux(state === tileDirty, tileDirty, tileClean), state))
  }
  def newStateOnHit(cmd: Bits, state: UFix): UFix = newState(cmd, state)
  def newStateOnCacheControl(cmd: Bits) = tileInvalid //TODO
  def newStateOnWriteback() = tileInvalid
  def newStateOnFlush() = tileInvalid
  def newStateOnTransactionReply(incoming: TransactionReply, outstanding: TransactionInit) = {
    MuxLookup(incoming.x_type, tileInvalid, Array(
      xactReplyData -> Mux(outstanding.x_type === xactInitReadDirty, tileDirty, tileClean),
      xactReplyAck  -> tileInvalid
    ))
  }

  def getTransactionInitTypeOnPrimaryMiss(cmd: Bits, state: UFix): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write || cmd === M_PFW, xactInitReadDirty, xactInitReadClean)
  }
  def getTransactionInitTypeOnSecondaryMiss(cmd: Bits, state: UFix, outstanding: TransactionInit): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, xactInitReadDirty, outstanding.x_type)
  }
  def getTransactionInitTypeOnCacheControl(cmd: Bits): Bits = xactInitWriteback //TODO
  def getTransactionInitTypeOnWriteback(): Bits = xactInitWriteback

  def messageHasData (init: TransactionInit): Bool = hasDataTypeList.map(t => init.x_type === t).reduceLeft(_||_)
  def messageHasData (reply: TransactionReply) = (reply.x_type === xactReplyData)
  def messageUpdatesDataArray (reply: TransactionReply) = (reply.x_type === xactReplyData)
  def messageIsUncached(init: TransactionInit): Bool = uncachedTypeList.map(t => init.x_type === t).reduceLeft(_||_)
}

class MICoherence extends CoherencePolicyWithUncached {

  val tileInvalid :: tileValid :: Nil = Enum(2){ UFix() }
  val globalInvalid :: globalValid :: Nil = Enum(2){ UFix() }

  val xactInitReadExclusive :: xactInitReadUncached :: xactInitWriteUncached :: xactInitReadWordUncached :: xactInitWriteWordUncached :: xactInitAtomicUncached :: Nil = Enum(6){ UFix() }
  val xactReplyReadExclusive :: xactReplyReadUncached :: xactReplyWriteUncached :: xactReplyReadWordUncached :: xactReplyWriteWordUncached :: xactReplyAtomicUncached :: Nil = Enum(6){ UFix() }
  val probeReqInvalidate :: probeReqCopy :: Nil = Enum(2){ UFix() }
  val probeRepInvalidateData :: probeRepCopyData :: probeRepInvalidateAck :: probeRepCopyAck :: Nil = Enum(4){ UFix() }
  val uncachedTypeList = List(xactInitReadUncached, xactInitWriteUncached, xactReplyReadWordUncached, xactInitWriteWordUncached, xactInitAtomicUncached) 
  val hasDataTypeList = List(xactInitWriteUncached, xactInitWriteWordUncached, xactInitAtomicUncached) 

  def isHit (cmd: Bits, state: UFix): Bool = state != tileInvalid
  def isValid (state: UFix): Bool = state != tileInvalid

  def needsTransactionOnSecondaryMiss(cmd: Bits, outstanding: TransactionInit): Bool = (outstanding.x_type != xactInitReadExclusive)
  def needsTransactionOnCacheControl(cmd: Bits, state: UFix): Bool = {
    MuxLookup(cmd, (state === tileValid), Array(
      M_INV -> (state === tileValid),
      M_CLN -> (state === tileValid)
    ))
  }
  def needsWriteback (state: UFix): Bool = {
    needsTransactionOnCacheControl(M_INV, state)
  }

  def newStateOnHit(cmd: Bits, state: UFix): UFix = state
  def newStateOnCacheControl(cmd: Bits) = {
    MuxLookup(cmd, tileInvalid, Array(
      M_INV -> tileInvalid,
      M_CLN -> tileValid
    ))
  }
  def newStateOnWriteback() = newStateOnCacheControl(M_INV)
  def newStateOnFlush() = newStateOnCacheControl(M_INV)
  def newStateOnTransactionReply(incoming: TransactionReply, outstanding: TransactionInit): UFix = {
    MuxLookup(incoming.x_type, tileInvalid, Array(
      xactReplyReadExclusive -> tileValid,
      xactReplyReadUncached  -> tileInvalid,
      xactReplyWriteUncached -> tileInvalid,
      xactReplyReadWordUncached -> tileInvalid,
      xactReplyWriteWordUncached -> tileInvalid,
      xactReplyAtomicUncached -> tileInvalid
    ))
  } 
  def newStateOnProbeRequest(incoming: ProbeRequest, state: UFix): Bits = {
    MuxLookup(incoming.p_type, state, Array(
      probeReqInvalidate -> tileInvalid,
      probeReqCopy       -> state
    ))
  }

  def getUncachedReadTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitReadUncached, addr, id)
  def getUncachedWriteTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitWriteUncached, addr, id)
  def getUncachedReadWordTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitReadWordUncached, addr, id)
  def getUncachedWriteWordTransactionInit(addr: UFix, id: UFix, write_mask: Bits) = TransactionInit(xactInitWriteWordUncached, addr, id, write_mask)
  def getUncachedAtomicTransactionInit(addr: UFix, id: UFix, subword_addr: UFix, atomic_op: UFix) = TransactionInit(xactInitAtomicUncached, addr, id, subword_addr, atomic_op)

  def getTransactionInitTypeOnPrimaryMiss(cmd: Bits, state: UFix): UFix = xactInitReadExclusive
  def getTransactionInitTypeOnSecondaryMiss(cmd: Bits, state: UFix, outstanding: TransactionInit): UFix = xactInitReadExclusive
  def getTransactionInitTypeOnCacheControl(cmd: Bits): Bits = xactInitWriteUncached
  def getTransactionInitTypeOnWriteback(): Bits = getTransactionInitTypeOnCacheControl(M_INV)

  def newProbeReply (incoming: ProbeRequest, state: UFix): ProbeReply = {
    val reply = new ProbeReply()
    val with_data = MuxLookup(incoming.p_type, probeRepInvalidateData, Array(
      probeReqInvalidate -> probeRepInvalidateData,
      probeReqCopy       -> probeRepCopyData
    ))
    val without_data = MuxLookup(incoming.p_type, probeRepInvalidateAck, Array(
      probeReqInvalidate -> probeRepInvalidateAck,
      probeReqCopy       -> probeRepCopyAck
    ))
    reply.p_type := Mux(needsWriteback(state), with_data, without_data)
    reply.global_xact_id := incoming.global_xact_id
    reply
  }

  def messageHasData (reply: ProbeReply): Bool = {
    (reply.p_type === probeRepInvalidateData ||
     reply.p_type === probeRepCopyData)
  }
  def messageHasData (init: TransactionInit): Bool = hasDataTypeList.map(t => init.x_type === t).reduceLeft(_||_)
  def messageHasData (reply: TransactionReply): Bool = {
    (reply.x_type != xactReplyWriteUncached && reply.x_type != xactReplyWriteWordUncached)
  }
  def messageUpdatesDataArray (reply: TransactionReply): Bool = {
    (reply.x_type === xactReplyReadExclusive)
  }
  def messageIsUncached(init: TransactionInit): Bool = uncachedTypeList.map(t => init.x_type === t).reduceLeft(_||_)

  def isCoherenceConflict(addr1: Bits, addr2: Bits): Bool = (addr1 === addr2)

  def getTransactionReplyType(x_type: UFix, count: UFix): Bits = {
    MuxLookup(x_type, xactReplyReadUncached, Array(
      xactInitReadExclusive -> xactReplyReadExclusive,
      xactInitReadUncached  -> xactReplyReadUncached,
      xactInitWriteUncached -> xactReplyWriteUncached,
      xactInitReadWordUncached  -> xactReplyReadWordUncached,
      xactInitWriteWordUncached -> xactReplyWriteWordUncached,
      xactInitAtomicUncached -> xactReplyAtomicUncached
    ))
  }

  def getProbeRequestType(x_type: UFix, global_state: UFix): UFix = {
    MuxLookup(x_type, probeReqCopy, Array(
      xactInitReadExclusive -> probeReqInvalidate, 
      xactInitReadUncached -> probeReqCopy, 
      xactInitWriteUncached -> probeReqInvalidate,
      xactInitReadWordUncached -> probeReqCopy, 
      xactInitWriteWordUncached -> probeReqInvalidate,
      xactInitAtomicUncached -> probeReqInvalidate
    ))
  }

  def needsMemRead(x_type: UFix, global_state: UFix): Bool = {
      (x_type != xactInitWriteUncached)
  }
  def needsMemWrite(x_type: UFix, global_state: UFix): Bool = {
      (x_type === xactInitWriteUncached)
  }
  def needsAckReply(x_type: UFix, global_state: UFix): Bool = {
      (x_type === xactInitWriteUncached)
  }
}

class MEICoherence extends CoherencePolicyWithUncached {

  val tileInvalid :: tileExclusiveClean :: tileExclusiveDirty :: Nil = Enum(3){ UFix() }
  val globalInvalid :: globalExclusiveClean :: Nil = Enum(2){ UFix() }

  val xactInitReadExclusiveClean :: xactInitReadExclusiveDirty :: xactInitReadUncached :: xactInitWriteUncached :: xactInitReadWordUncached :: xactInitWriteWordUncached :: xactInitAtomicUncached :: Nil = Enum(7){ UFix() }
  val xactReplyReadExclusive :: xactReplyReadUncached :: xactReplyWriteUncached :: xactReplyReadExclusiveAck :: xactReplyReadWordUncached :: xactReplyWriteWordUncached :: xactReplyAtomicUncached :: Nil = Enum(7){ UFix() }
  val probeReqInvalidate :: probeReqDowngrade :: probeReqCopy :: Nil = Enum(3){ UFix() }
  val probeRepInvalidateData :: probeRepDowngradeData :: probeRepCopyData :: probeRepInvalidateAck :: probeRepDowngradeAck :: probeRepCopyAck :: Nil = Enum(6){ UFix() }
  val uncachedTypeList = List(xactInitReadUncached, xactInitWriteUncached, xactReplyReadWordUncached, xactInitWriteWordUncached, xactInitAtomicUncached) 
  val hasDataTypeList = List(xactInitWriteUncached, xactInitWriteWordUncached, xactInitAtomicUncached) 

  def isHit (cmd: Bits, state: UFix): Bool = state != tileInvalid
  def isValid (state: UFix): Bool = state != tileInvalid

  def needsTransactionOnSecondaryMiss(cmd: Bits, outstanding: TransactionInit): Bool = {
    val (read, write) = cpuCmdToRW(cmd)
    (read && messageIsUncached(outstanding)) ||
      (write && (outstanding.x_type != xactInitReadExclusiveDirty))
  }
  def needsTransactionOnCacheControl(cmd: Bits, state: UFix): Bool = {
    MuxLookup(cmd, (state === tileExclusiveDirty), Array(
      M_INV -> (state === tileExclusiveDirty),
      M_CLN -> (state === tileExclusiveDirty)
    ))
  }
  def needsWriteback (state: UFix): Bool = {
    needsTransactionOnCacheControl(M_INV, state)
  }

  def newStateOnHit(cmd: Bits, state: UFix): UFix = { 
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, tileExclusiveDirty, state)
  }
  def newStateOnCacheControl(cmd: Bits) = {
    MuxLookup(cmd, tileInvalid, Array(
      M_INV -> tileInvalid,
      M_CLN -> tileExclusiveClean
    ))
  }
  def newStateOnWriteback() = newStateOnCacheControl(M_INV)
  def newStateOnFlush() = newStateOnCacheControl(M_INV)
  def newStateOnTransactionReply(incoming: TransactionReply, outstanding: TransactionInit): UFix = {
    MuxLookup(incoming.x_type, tileInvalid, Array(
      xactReplyReadExclusive  -> Mux(outstanding.x_type === xactInitReadExclusiveDirty, tileExclusiveDirty, tileExclusiveClean),
      xactReplyReadExclusiveAck -> tileExclusiveDirty, 
      xactReplyReadUncached -> tileInvalid,
      xactReplyWriteUncached -> tileInvalid,
      xactReplyReadWordUncached -> tileInvalid,
      xactReplyWriteWordUncached -> tileInvalid,
      xactReplyAtomicUncached -> tileInvalid
    ))
  } 
  def newStateOnProbeRequest(incoming: ProbeRequest, state: UFix): Bits = {
    MuxLookup(incoming.p_type, state, Array(
      probeReqInvalidate -> tileInvalid,
      probeReqDowngrade  -> tileExclusiveClean,
      probeReqCopy       -> state
    ))
  }

  def getUncachedReadTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitReadUncached, addr, id)
  def getUncachedWriteTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitWriteUncached, addr, id)
  def getUncachedReadWordTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitReadWordUncached, addr, id)
  def getUncachedWriteWordTransactionInit(addr: UFix, id: UFix, write_mask: Bits) = TransactionInit(xactInitWriteWordUncached, addr, id, write_mask)
  def getUncachedAtomicTransactionInit(addr: UFix, id: UFix, subword_addr: UFix, atomic_op: UFix) = TransactionInit(xactInitAtomicUncached, addr, id, subword_addr, atomic_op)

  def getTransactionInitTypeOnPrimaryMiss(cmd: Bits, state: UFix): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, xactInitReadExclusiveDirty, xactInitReadExclusiveClean)
  }
  def getTransactionInitTypeOnSecondaryMiss(cmd: Bits, state: UFix, outstanding: TransactionInit): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, xactInitReadExclusiveDirty, outstanding.x_type)
  }
  def getTransactionInitTypeOnCacheControl(cmd: Bits): Bits = xactInitWriteUncached
  def getTransactionInitTypeOnWriteback(): Bits = getTransactionInitTypeOnCacheControl(M_INV)

  def newProbeReply (incoming: ProbeRequest, state: UFix): ProbeReply = {
    val reply = new ProbeReply()
    val with_data = MuxLookup(incoming.p_type, probeRepInvalidateData, Array(
      probeReqInvalidate -> probeRepInvalidateData,
      probeReqDowngrade  -> probeRepDowngradeData,
      probeReqCopy       -> probeRepCopyData
    ))
    val without_data = MuxLookup(incoming.p_type, probeRepInvalidateAck, Array(
      probeReqInvalidate -> probeRepInvalidateAck,
      probeReqDowngrade  -> probeRepDowngradeAck,
      probeReqCopy       -> probeRepCopyAck
    ))
    reply.p_type := Mux(needsWriteback(state), with_data, without_data)
    reply.global_xact_id := incoming.global_xact_id
    reply
  }

  def messageHasData (reply: ProbeReply): Bool = {
    (reply.p_type === probeRepInvalidateData ||
     reply.p_type === probeRepDowngradeData ||
     reply.p_type === probeRepCopyData)
  }
  def messageHasData (init: TransactionInit): Bool = hasDataTypeList.map(t => init.x_type === t).reduceLeft(_||_)
  def messageHasData (reply: TransactionReply): Bool = {
    (reply.x_type != xactReplyWriteUncached && reply.x_type != xactReplyReadExclusiveAck && reply.x_type != xactReplyWriteWordUncached)
  }
  def messageUpdatesDataArray (reply: TransactionReply): Bool = {
    (reply.x_type === xactReplyReadExclusive)
  }
  def messageIsUncached(init: TransactionInit): Bool = uncachedTypeList.map(t => init.x_type === t).reduceLeft(_||_)

  def isCoherenceConflict(addr1: Bits, addr2: Bits): Bool = (addr1 === addr2)

  def getTransactionReplyType(x_type: UFix, count: UFix): Bits = {
    MuxLookup(x_type, xactReplyReadUncached, Array(
      xactInitReadExclusiveClean -> xactReplyReadExclusive,
      xactInitReadExclusiveDirty -> xactReplyReadExclusive,
      xactInitReadUncached  -> xactReplyReadUncached,
      xactInitWriteUncached -> xactReplyWriteUncached,
      xactInitReadWordUncached  -> xactReplyReadWordUncached,
      xactInitWriteWordUncached -> xactReplyWriteWordUncached,
      xactInitAtomicUncached -> xactReplyAtomicUncached
    ))
  }

  def getProbeRequestType(x_type: UFix, global_state: UFix): UFix = {
    MuxLookup(x_type, probeReqCopy, Array(
      xactInitReadExclusiveClean -> probeReqInvalidate,
      xactInitReadExclusiveDirty -> probeReqInvalidate, 
      xactInitReadUncached -> probeReqCopy, 
      xactInitWriteUncached -> probeReqInvalidate,
      xactInitReadWordUncached -> probeReqCopy, 
      xactInitWriteWordUncached -> probeReqInvalidate,
      xactInitAtomicUncached -> probeReqInvalidate
    ))
  }

  def needsMemRead(x_type: UFix, global_state: UFix): Bool = {
      (x_type != xactInitWriteUncached)
  }
  def needsMemWrite(x_type: UFix, global_state: UFix): Bool = {
      (x_type === xactInitWriteUncached)
  }
  def needsAckReply(x_type: UFix, global_state: UFix): Bool = {
      (x_type === xactInitWriteUncached)
  }
}

class MSICoherence extends CoherencePolicyWithUncached {

  val tileInvalid :: tileShared :: tileExclusiveDirty :: Nil = Enum(3){ UFix() }
  val globalInvalid :: globalShared :: globalExclusive :: Nil = Enum(3){ UFix() }

  val xactInitReadShared :: xactInitReadExclusive :: xactInitReadUncached :: xactInitWriteUncached :: xactInitReadWordUncached :: xactInitWriteWordUncached :: xactInitAtomicUncached :: Nil = Enum(7){ UFix() }
  val xactReplyReadShared :: xactReplyReadExclusive :: xactReplyReadUncached :: xactReplyWriteUncached :: xactReplyReadExclusiveAck :: xactReplyReadWordUncached :: xactReplyWriteWordUncached :: xactReplyAtomicUncached :: Nil = Enum(8){ UFix() }
  val probeReqInvalidate :: probeReqDowngrade :: probeReqCopy :: Nil = Enum(3){ UFix() }
  val probeRepInvalidateData :: probeRepDowngradeData :: probeRepCopyData :: probeRepInvalidateAck :: probeRepDowngradeAck :: probeRepCopyAck :: Nil = Enum(6){ UFix() }
  val uncachedTypeList = List(xactInitReadUncached, xactInitWriteUncached, xactReplyReadWordUncached, xactInitWriteWordUncached, xactInitAtomicUncached) 
  val hasDataTypeList = List(xactInitWriteUncached, xactInitWriteWordUncached, xactInitAtomicUncached) 

  def isHit (cmd: Bits, state: UFix): Bool = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, (state === tileExclusiveDirty),
        (state === tileShared || state === tileExclusiveDirty))
  }
  def isValid (state: UFix): Bool = {
    state != tileInvalid
  }

  def needsTransactionOnSecondaryMiss(cmd: Bits, outstanding: TransactionInit): Bool = {
    val (read, write) = cpuCmdToRW(cmd)
    (read && messageIsUncached(outstanding)) || 
      (write && (outstanding.x_type != xactInitReadExclusive))
  }
  def needsTransactionOnCacheControl(cmd: Bits, state: UFix): Bool = {
    MuxLookup(cmd, (state === tileExclusiveDirty), Array(
      M_INV -> (state === tileExclusiveDirty),
      M_CLN -> (state === tileExclusiveDirty)
    ))
  }
  def needsWriteback (state: UFix): Bool = {
    needsTransactionOnCacheControl(M_INV, state)
  }

  def newStateOnHit(cmd: Bits, state: UFix): UFix = { 
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, tileExclusiveDirty, state)
  }
  def newStateOnCacheControl(cmd: Bits) = {
    MuxLookup(cmd, tileInvalid, Array(
      M_INV -> tileInvalid,
      M_CLN -> tileShared
    ))
  }
  def newStateOnWriteback() = newStateOnCacheControl(M_INV)
  def newStateOnFlush() = newStateOnCacheControl(M_INV)
  def newStateOnTransactionReply(incoming: TransactionReply, outstanding: TransactionInit): UFix = {
    MuxLookup(incoming.x_type, tileInvalid, Array(
      xactReplyReadShared -> tileShared,
      xactReplyReadExclusive  -> tileExclusiveDirty,
      xactReplyReadExclusiveAck -> tileExclusiveDirty, 
      xactReplyReadUncached -> tileInvalid,
      xactReplyWriteUncached -> tileInvalid,
      xactReplyReadWordUncached -> tileInvalid,
      xactReplyWriteWordUncached -> tileInvalid,
      xactReplyAtomicUncached -> tileInvalid
    ))
  } 
  def newStateOnProbeRequest(incoming: ProbeRequest, state: UFix): Bits = {
    MuxLookup(incoming.p_type, state, Array(
      probeReqInvalidate -> tileInvalid,
      probeReqDowngrade  -> tileShared,
      probeReqCopy       -> state
    ))
  }

  def getUncachedReadTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitReadUncached, addr, id)
  def getUncachedWriteTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitWriteUncached, addr, id)
  def getUncachedReadWordTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitReadWordUncached, addr, id)
  def getUncachedWriteWordTransactionInit(addr: UFix, id: UFix, write_mask: Bits) = TransactionInit(xactInitWriteWordUncached, addr, id, write_mask)
  def getUncachedAtomicTransactionInit(addr: UFix, id: UFix, subword_addr: UFix, atomic_op: UFix) = TransactionInit(xactInitAtomicUncached, addr, id, subword_addr, atomic_op)

  def getTransactionInitTypeOnPrimaryMiss(cmd: Bits, state: UFix): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write || cmd === M_PFW, xactInitReadExclusive, xactInitReadShared)
  }
  def getTransactionInitTypeOnSecondaryMiss(cmd: Bits, state: UFix, outstanding: TransactionInit): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, xactInitReadExclusive, outstanding.x_type)
  }
  def getTransactionInitTypeOnCacheControl(cmd: Bits): Bits = xactInitWriteUncached
  def getTransactionInitTypeOnWriteback(): Bits = getTransactionInitTypeOnCacheControl(M_INV)

  def newProbeReply (incoming: ProbeRequest, state: UFix): ProbeReply = {
    val reply = new ProbeReply()
    val with_data = MuxLookup(incoming.p_type, probeRepInvalidateData, Array(
      probeReqInvalidate -> probeRepInvalidateData,
      probeReqDowngrade  -> probeRepDowngradeData,
      probeReqCopy       -> probeRepCopyData
    ))
    val without_data = MuxLookup(incoming.p_type, probeRepInvalidateAck, Array(
      probeReqInvalidate -> probeRepInvalidateAck,
      probeReqDowngrade  -> probeRepDowngradeAck,
      probeReqCopy       -> probeRepCopyAck
    ))
    reply.p_type := Mux(needsWriteback(state), with_data, without_data)
    reply.global_xact_id := incoming.global_xact_id
    reply
  }

  def messageHasData (reply: ProbeReply): Bool = {
    (reply.p_type === probeRepInvalidateData ||
     reply.p_type === probeRepDowngradeData ||
     reply.p_type === probeRepCopyData)
  }
  def messageHasData (init: TransactionInit): Bool = hasDataTypeList.map(t => init.x_type === t).reduceLeft(_||_)
  def messageHasData (reply: TransactionReply): Bool = {
    (reply.x_type != xactReplyWriteUncached && reply.x_type != xactReplyReadExclusiveAck && reply.x_type != xactReplyWriteWordUncached)
  }
  def messageUpdatesDataArray (reply: TransactionReply): Bool = {
    (reply.x_type === xactReplyReadShared || reply.x_type === xactReplyReadExclusive)
  }
  def messageIsUncached(init: TransactionInit): Bool = uncachedTypeList.map(t => init.x_type === t).reduceLeft(_||_)

  def isCoherenceConflict(addr1: Bits, addr2: Bits): Bool = (addr1 === addr2)

  def getTransactionReplyType(x_type: UFix, count: UFix): Bits = {
    MuxLookup(x_type, xactReplyReadUncached, Array(
      xactInitReadShared    -> Mux(count > UFix(0), xactReplyReadShared, xactReplyReadExclusive),
      xactInitReadExclusive -> xactReplyReadExclusive,
      xactInitReadUncached  -> xactReplyReadUncached,
      xactInitWriteUncached -> xactReplyWriteUncached,
      xactInitReadWordUncached  -> xactReplyReadWordUncached,
      xactInitWriteWordUncached -> xactReplyWriteWordUncached,
      xactInitAtomicUncached -> xactReplyAtomicUncached
    ))
  }

  def getProbeRequestType(x_type: UFix, global_state: UFix): UFix = {
    MuxLookup(x_type, probeReqCopy, Array(
      xactInitReadShared -> probeReqDowngrade,
      xactInitReadExclusive -> probeReqInvalidate, 
      xactInitReadUncached -> probeReqCopy, 
      xactInitWriteUncached -> probeReqInvalidate
    ))
  }

  def needsMemRead(x_type: UFix, global_state: UFix): Bool = {
      (x_type != xactInitWriteUncached)
  }
  def needsMemWrite(x_type: UFix, global_state: UFix): Bool = {
      (x_type === xactInitWriteUncached)
  }
  def needsAckReply(x_type: UFix, global_state: UFix): Bool = {
      (x_type === xactInitWriteUncached)
  }
}

class MESICoherence extends CoherencePolicyWithUncached {

  val tileInvalid :: tileShared :: tileExclusiveClean :: tileExclusiveDirty :: Nil = Enum(4){ UFix() }
  val globalInvalid :: globalShared :: globalExclusiveClean :: Nil = Enum(3){ UFix() }

  val xactInitReadShared :: xactInitReadExclusive :: xactInitReadUncached :: xactInitWriteUncached :: xactInitReadWordUncached :: xactInitWriteWordUncached :: xactInitAtomicUncached :: Nil = Enum(7){ UFix() }
  val xactReplyReadShared :: xactReplyReadExclusive :: xactReplyReadUncached :: xactReplyWriteUncached :: xactReplyReadExclusiveAck :: xactReplyReadWordUncached :: xactReplyWriteWordUncached :: xactReplyAtomicUncached :: Nil = Enum(8){ UFix() }
  val probeReqInvalidate :: probeReqDowngrade :: probeReqCopy :: Nil = Enum(3){ UFix() }
  val probeRepInvalidateData :: probeRepDowngradeData :: probeRepCopyData :: probeRepInvalidateAck :: probeRepDowngradeAck :: probeRepCopyAck :: Nil = Enum(6){ UFix() }
  val uncachedTypeList = List(xactInitReadUncached, xactInitWriteUncached, xactReplyReadWordUncached, xactInitWriteWordUncached, xactInitAtomicUncached) 
  val hasDataTypeList = List(xactInitWriteUncached, xactInitWriteWordUncached, xactInitAtomicUncached) 

  def isHit (cmd: Bits, state: UFix): Bool = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, (state === tileExclusiveClean || state === tileExclusiveDirty),
        (state === tileShared || state === tileExclusiveClean || state === tileExclusiveDirty))
  }
  def isValid (state: UFix): Bool = {
    state != tileInvalid
  }

  def needsTransactionOnSecondaryMiss(cmd: Bits, outstanding: TransactionInit): Bool = {
    val (read, write) = cpuCmdToRW(cmd)
    (read && messageIsUncached(outstanding)) ||
      (write && (outstanding.x_type != xactInitReadExclusive))
  }
  def needsTransactionOnCacheControl(cmd: Bits, state: UFix): Bool = {
    MuxLookup(cmd, (state === tileExclusiveDirty), Array(
      M_INV -> (state === tileExclusiveDirty),
      M_CLN -> (state === tileExclusiveDirty)
    ))
  }
  def needsWriteback (state: UFix): Bool = {
    needsTransactionOnCacheControl(M_INV, state)
  }

  def newStateOnHit(cmd: Bits, state: UFix): UFix = { 
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, tileExclusiveDirty, state)
  }
  def newStateOnCacheControl(cmd: Bits) = {
    MuxLookup(cmd, tileInvalid, Array(
      M_INV -> tileInvalid,
      M_CLN -> tileShared
    ))
  }
  def newStateOnWriteback() = newStateOnCacheControl(M_INV)
  def newStateOnFlush() = newStateOnCacheControl(M_INV)
  def newStateOnTransactionReply(incoming: TransactionReply, outstanding: TransactionInit): UFix = {
    MuxLookup(incoming.x_type, tileInvalid, Array(
      xactReplyReadShared -> tileShared,
      xactReplyReadExclusive  -> Mux(outstanding.x_type === xactInitReadExclusive, tileExclusiveDirty, tileExclusiveClean),
      xactReplyReadExclusiveAck -> tileExclusiveDirty, 
      xactReplyReadUncached -> tileInvalid,
      xactReplyWriteUncached -> tileInvalid,
      xactReplyReadWordUncached -> tileInvalid,
      xactReplyWriteWordUncached -> tileInvalid,
      xactReplyAtomicUncached -> tileInvalid
    ))
  } 
  def newStateOnProbeRequest(incoming: ProbeRequest, state: UFix): Bits = {
    MuxLookup(incoming.p_type, state, Array(
      probeReqInvalidate -> tileInvalid,
      probeReqDowngrade  -> tileShared,
      probeReqCopy       -> state
    ))
  }

  def getUncachedReadTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitReadUncached, addr, id)
  def getUncachedWriteTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitWriteUncached, addr, id)
  def getUncachedReadWordTransactionInit(addr: UFix, id: UFix) = TransactionInit(xactInitReadWordUncached, addr, id)
  def getUncachedWriteWordTransactionInit(addr: UFix, id: UFix, write_mask: Bits) = TransactionInit(xactInitWriteWordUncached, addr, id, write_mask)
  def getUncachedAtomicTransactionInit(addr: UFix, id: UFix, subword_addr: UFix, atomic_op: UFix) = TransactionInit(xactInitAtomicUncached, addr, id, subword_addr, atomic_op)

  def getTransactionInitTypeOnPrimaryMiss(cmd: Bits, state: UFix): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write || cmd === M_PFW, xactInitReadExclusive, xactInitReadShared)
  }
  def getTransactionInitTypeOnSecondaryMiss(cmd: Bits, state: UFix, outstanding: TransactionInit): UFix = {
    val (read, write) = cpuCmdToRW(cmd)
    Mux(write, xactInitReadExclusive, outstanding.x_type)
  }
  def getTransactionInitTypeOnCacheControl(cmd: Bits): Bits = xactInitWriteUncached
  def getTransactionInitTypeOnWriteback(): Bits = getTransactionInitTypeOnCacheControl(M_INV)

  def newProbeReply (incoming: ProbeRequest, state: UFix): ProbeReply = {
    val reply = new ProbeReply()
    val with_data = MuxLookup(incoming.p_type, probeRepInvalidateData, Array(
      probeReqInvalidate -> probeRepInvalidateData,
      probeReqDowngrade  -> probeRepDowngradeData,
      probeReqCopy       -> probeRepCopyData
    ))
    val without_data = MuxLookup(incoming.p_type, probeRepInvalidateAck, Array(
      probeReqInvalidate -> probeRepInvalidateAck,
      probeReqDowngrade  -> probeRepDowngradeAck,
      probeReqCopy       -> probeRepCopyAck
    ))
    reply.p_type := Mux(needsWriteback(state), with_data, without_data)
    reply.global_xact_id := incoming.global_xact_id
    reply
  }

  def messageHasData (reply: ProbeReply): Bool = {
    (reply.p_type === probeRepInvalidateData ||
     reply.p_type === probeRepDowngradeData ||
     reply.p_type === probeRepCopyData)
  }
  def messageHasData (init: TransactionInit): Bool = hasDataTypeList.map(t => init.x_type === t).reduceLeft(_||_)
  def messageHasData (reply: TransactionReply): Bool = {
    (reply.x_type != xactReplyWriteUncached && reply.x_type != xactReplyReadExclusiveAck && reply.x_type != xactReplyWriteWordUncached)
  }
  def messageUpdatesDataArray (reply: TransactionReply): Bool = {
    (reply.x_type === xactReplyReadShared || reply.x_type === xactReplyReadExclusive)
  }
  def messageIsUncached(init: TransactionInit): Bool = uncachedTypeList.map(t => init.x_type === t).reduceLeft(_||_)

  def isCoherenceConflict(addr1: Bits, addr2: Bits): Bool = (addr1 === addr2)

  def getTransactionReplyType(x_type: UFix, count: UFix): Bits = {
    MuxLookup(x_type, xactReplyReadUncached, Array(
      xactInitReadShared    -> Mux(count > UFix(0), xactReplyReadShared, xactReplyReadExclusive),
      xactInitReadExclusive -> xactReplyReadExclusive,
      xactInitReadUncached  -> xactReplyReadUncached,
      xactInitWriteUncached -> xactReplyWriteUncached,
      xactInitReadWordUncached  -> xactReplyReadWordUncached,
      xactInitWriteWordUncached -> xactReplyWriteWordUncached,
      xactInitAtomicUncached -> xactReplyAtomicUncached
    ))
  }

  def getProbeRequestType(x_type: UFix, global_state: UFix): UFix = {
    MuxLookup(x_type, probeReqCopy, Array(
      xactInitReadShared -> probeReqDowngrade,
      xactInitReadExclusive -> probeReqInvalidate, 
      xactInitReadUncached -> probeReqCopy, 
      xactInitWriteUncached -> probeReqInvalidate,
      xactInitReadWordUncached -> probeReqCopy, 
      xactInitWriteWordUncached -> probeReqInvalidate,
      xactInitAtomicUncached -> probeReqInvalidate
    ))
  }

  def needsMemRead(x_type: UFix, global_state: UFix): Bool = {
      (x_type != xactInitWriteUncached)
  }
  def needsMemWrite(x_type: UFix, global_state: UFix): Bool = {
      (x_type === xactInitWriteUncached)
  }
  def needsAckReply(x_type: UFix, global_state: UFix): Bool = {
      (x_type === xactInitWriteUncached)
  }
}
